package org.jellyfin.androidtv.ui.browsing;

import static org.koin.java.KoinJavaComponent.inject;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.app.RowsSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ClassPresenterSelector;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.OnItemViewSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.constant.CustomMessage;
import org.jellyfin.androidtv.constant.Extras;
import org.jellyfin.androidtv.constant.LiveTvOption;
import org.jellyfin.androidtv.constant.QueryType;
import org.jellyfin.androidtv.data.model.DataRefreshService;
import org.jellyfin.androidtv.data.querying.ViewQuery;
import org.jellyfin.androidtv.data.repository.CustomMessageRepository;
import org.jellyfin.androidtv.data.service.BackgroundService;
import org.jellyfin.androidtv.databinding.EnhancedDetailBrowseBinding;
import org.jellyfin.androidtv.ui.GridButton;
import org.jellyfin.androidtv.ui.itemdetail.ItemListFragment;
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem;
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher;
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter;
import org.jellyfin.androidtv.ui.navigation.Destinations;
import org.jellyfin.androidtv.ui.navigation.NavigationRepository;
import org.jellyfin.androidtv.ui.playback.MediaManager;
import org.jellyfin.androidtv.ui.presentation.CardPresenter;
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter;
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter;
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter;
import org.jellyfin.androidtv.util.CoroutineUtils;
import org.jellyfin.androidtv.util.InfoLayoutHelper;
import org.jellyfin.androidtv.util.KeyProcessor;
import org.jellyfin.androidtv.util.MarkdownRenderer;
import org.jellyfin.androidtv.util.sdk.compat.ModelCompat;
import org.jellyfin.apiclient.interaction.EmptyResponse;
import org.jellyfin.apiclient.model.dto.BaseItemDto;
import org.jellyfin.apiclient.model.dto.BaseItemType;
import org.jellyfin.apiclient.serialization.GsonJsonSerializer;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.jellyfin.sdk.model.constant.CollectionType;
import org.jellyfin.sdk.model.serializer.UUIDSerializerKt;
import org.koin.java.KoinJavaComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import kotlin.Lazy;

public class EnhancedBrowseFragment extends Fragment implements RowLoader, View.OnKeyListener {
    protected FragmentActivity mActivity;

    protected TextView mTitle;
    private LinearLayout mInfoRow;
    private TextView mSummary;

    protected static final int BY_LETTER = 0;
    protected static final int GENRES = 1;
    protected static final int SUGGESTED = 4;
    protected static final int GRID = 6;
    protected static final int ALBUMS = 7;
    protected static final int ARTISTS = 8;
    public static final int FAVSONGS = 9;
    protected static final int SCHEDULE = 10;
    protected static final int SERIES = 11;
    protected BaseItemDto mFolder;
    protected String itemTypeString;
    protected boolean showViews = true;
    protected boolean justLoaded = true;

    protected BaseRowItem favSongsRowItem;

    protected RowsSupportFragment mRowsFragment;
    protected CompositeClickedListener mClickedListener = new CompositeClickedListener();
    protected CompositeSelectedListener mSelectedListener = new CompositeSelectedListener();
    protected MutableObjectAdapter<Row> mRowsAdapter;
    protected ArrayList<BrowseRowDef> mRows = new ArrayList<>();
    protected CardPresenter mCardPresenter;
    protected BaseRowItem mCurrentItem;
    protected ListRow mCurrentRow;

    private Lazy<GsonJsonSerializer> serializer = inject(GsonJsonSerializer.class);
    private Lazy<BackgroundService> backgroundService = inject(BackgroundService.class);
    private Lazy<MediaManager> mediaManager = inject(MediaManager.class);
    private Lazy<MarkdownRenderer> markdownRenderer = inject(MarkdownRenderer.class);
    private final Lazy<CustomMessageRepository> customMessageRepository = inject(CustomMessageRepository.class);
    private final Lazy<NavigationRepository> navigationRepository = inject(NavigationRepository.class);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BaseItemDto item = new BaseItemDto();
        item.setId(ItemListFragment.FAV_SONGS);
        item.setBaseItemType(BaseItemType.Playlist);
        item.setIsFolder(true);

        favSongsRowItem = new BaseRowItem(0, item);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        EnhancedDetailBrowseBinding binding = EnhancedDetailBrowseBinding.inflate(inflater, container, false);

        mActivity = getActivity();

        mTitle = binding.title;
        mInfoRow = binding.infoRow;
        mSummary = binding.summary;

        // Inject the RowsSupportFragment in the results container
        if (getChildFragmentManager().findFragmentById(R.id.rowsFragment) == null) {
            mRowsFragment = new RowsSupportFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.rowsFragment, mRowsFragment).commit();
        } else {
            mRowsFragment = (RowsSupportFragment) getChildFragmentManager()
                    .findFragmentById(R.id.rowsFragment);
        }

        mRowsAdapter = new MutableObjectAdapter<Row>(new PositionableListRowPresenter());
        mRowsFragment.setAdapter(mRowsAdapter);

        return binding.getRoot();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        backgroundService.getValue().attach(requireActivity());

        setupViews();
        setupQueries(this);
        setupEventListeners();
    }

    protected void setupQueries(RowLoader rowLoader) {
        rowLoader.loadRows(mRows);
    }

    protected void setupViews() {
        mFolder = serializer.getValue().DeserializeFromString(getArguments().getString(Extras.Folder), BaseItemDto.class);
        if (mFolder == null) return;

        if (mFolder.getCollectionType() != null) {
            switch (mFolder.getCollectionType()) {
                case CollectionType.Movies:
                    itemTypeString = "Movie";
                    break;
                case CollectionType.TvShows:
                    itemTypeString = "Series";
                    break;
                case CollectionType.Music:
                    itemTypeString = "MusicAlbum";
                    break;
                case CollectionType.Folders:
                    showViews = false;
                    break;
                default:
                    showViews = false;
            }
        } else {
            showViews = false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // React to deletion
        DataRefreshService dataRefreshService = KoinJavaComponent.<DataRefreshService>get(DataRefreshService.class);
        if (getActivity() != null && !getActivity().isFinishing() && mCurrentRow != null && mCurrentItem != null && mCurrentItem.getItemId() != null && mCurrentItem.getItemId().equals(dataRefreshService.getLastDeletedItemId())) {
            ((ItemRowAdapter) mCurrentRow.getAdapter()).remove(mCurrentItem);
            dataRefreshService.setLastDeletedItemId(null);
        }

        if (!justLoaded) {
            // Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
            if (mRowsAdapter != null) {
                refreshCurrentItem();
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mActivity.isFinishing()) return;
                        for (int i = 0; i < mRowsAdapter.size(); i++) {
                            if (mRowsAdapter.get(i) instanceof ListRow) {
                                if (((ListRow) mRowsAdapter.get(i)).getAdapter() instanceof ItemRowAdapter && !mActivity.isFinishing()) {
                                    ((ItemRowAdapter) ((ListRow) mRowsAdapter.get(i)).getAdapter()).ReRetrieveIfNeeded();
                                }
                            }
                        }
                    }
                }, 1500);
            }
        } else {
            justLoaded = false;
        }
    }

    public void loadRows(List<BrowseRowDef> rows) {
        mRowsAdapter = new MutableObjectAdapter<Row>(new PositionableListRowPresenter());
        mCardPresenter = new CardPresenter(false, 280);
        ClassPresenterSelector ps = new ClassPresenterSelector();
        ps.addClassPresenter(BaseRowItem.class, mCardPresenter);
        ps.addClassPresenter(GridButton.class, new GridButtonPresenter(false, 310, 280));

        for (BrowseRowDef def : rows) {
            HeaderItem header = new HeaderItem(def.getHeaderText());
            ItemRowAdapter rowAdapter;
            switch (def.getQueryType()) {
                case NextUp:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getNextUpQuery(), true, mCardPresenter, mRowsAdapter);
                    break;
                case LatestItems:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getLatestItemsQuery(), true, mCardPresenter, mRowsAdapter);
                    break;
                case Season:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getSeasonQuery(), mCardPresenter, mRowsAdapter);
                    break;
                case Upcoming:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getUpcomingQuery(), mCardPresenter, mRowsAdapter);
                    break;
                case Views:
                    rowAdapter = new ItemRowAdapter(requireContext(), ViewQuery.INSTANCE, mCardPresenter, mRowsAdapter);
                    break;
                case SimilarSeries:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getSimilarQuery(), QueryType.SimilarSeries, mCardPresenter, mRowsAdapter);
                    break;
                case SimilarMovies:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getSimilarQuery(), QueryType.SimilarMovies, mCardPresenter, mRowsAdapter);
                    break;
                case Persons:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getPersonsQuery(), def.getChunkSize(), mCardPresenter, mRowsAdapter);
                    break;
                case LiveTvChannel:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getTvChannelQuery(), 40, mCardPresenter, mRowsAdapter);
                    break;
                case LiveTvProgram:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getProgramQuery(), mCardPresenter, mRowsAdapter);
                    break;
                case LiveTvRecording:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getRecordingQuery(), def.getChunkSize(), mCardPresenter, mRowsAdapter);
                    break;
                case Premieres:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getQuery(), def.getChunkSize(), def.getPreferParentThumb(), def.isStaticHeight(), mCardPresenter, mRowsAdapter, def.getQueryType());
                    break;
                case SeriesTimer:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getSeriesTimerQuery(), mCardPresenter, mRowsAdapter);
                    break;
                default:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getQuery(), def.getChunkSize(), def.getPreferParentThumb(), def.isStaticHeight(), ps, mRowsAdapter, def.getQueryType());
                    break;
            }

            rowAdapter.setReRetrieveTriggers(def.getChangeTriggers());

            ListRow row = new ListRow(header, rowAdapter);
            mRowsAdapter.add(row);
            rowAdapter.setRow(row);
            rowAdapter.Retrieve();
        }

        addAdditionalRows(mRowsAdapter);

        mRowsFragment.setAdapter(mRowsAdapter);
    }

    protected void addAdditionalRows(MutableObjectAdapter<Row> rowAdapter) {
        if (!showViews) return;

        HeaderItem gridHeader = new HeaderItem(rowAdapter.size(), getString(R.string.lbl_views));
        GridButtonPresenter mGridPresenter = new GridButtonPresenter();
        ArrayObjectAdapter gridRowAdapter = new ArrayObjectAdapter(mGridPresenter);

        switch (itemTypeString) {
            case "Movie":
                gridRowAdapter.add(new GridButton(SUGGESTED, getString(R.string.lbl_suggested), R.drawable.tile_suggestions));
                addStandardViewButtons(gridRowAdapter);
                break;

            case "MusicAlbum":
                gridRowAdapter.add(new GridButton(ALBUMS, getString(R.string.lbl_albums), R.drawable.tile_audio));
                gridRowAdapter.add(new GridButton(ARTISTS, getString(R.string.lbl_artists), R.drawable.tile_artists));
                gridRowAdapter.add(new GridButton(GENRES, getString(R.string.lbl_genres), R.drawable.tile_genres));
                break;

            default:
                addStandardViewButtons(gridRowAdapter);
                break;
        }

        rowAdapter.add(new ListRow(gridHeader, gridRowAdapter));
    }

    protected void addStandardViewButtons(ArrayObjectAdapter gridRowAdapter) {
        gridRowAdapter.add(new GridButton(GRID, getString(R.string.lbl_all_items), R.drawable.tile_port_grid));
        gridRowAdapter.add(new GridButton(BY_LETTER, getString(R.string.lbl_by_letter), R.drawable.tile_letters));
        gridRowAdapter.add(new GridButton(GENRES, getString(R.string.lbl_genres), R.drawable.tile_genres));
        // Disabled because the screen doesn't behave properly
        // gridRowAdapter.add(new GridButton(PERSONS, getString(R.string.lbl_performers), R.drawable.tile_actors));
    }

    protected void setupEventListeners() {
        mRowsFragment.setOnItemViewClickedListener(mClickedListener);
        mClickedListener.registerListener(new ItemViewClickedListener());
        if (showViews) mClickedListener.registerListener(new SpecialViewClickedListener());

        mRowsFragment.setOnItemViewSelectedListener(mSelectedListener);
        mSelectedListener.registerListener(new ItemViewSelectedListener());

        CoroutineUtils.readCustomMessagesOnLifecycle(getLifecycle(), customMessageRepository.getValue(), message -> {
            if (message.equals(CustomMessage.RefreshCurrentItem.INSTANCE)) refreshCurrentItem();
            return null;
        });
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        return KeyProcessor.HandleKey(keyCode, mCurrentItem, mActivity);
    }

    private void refreshCurrentItem() {
        if (mCurrentItem != null &&
                mCurrentItem.getBaseItemType() != BaseItemKind.PHOTO &&
                mCurrentItem.getBaseItemType() != BaseItemKind.MUSIC_ARTIST &&
                mCurrentItem.getBaseItemType() != BaseItemKind.MUSIC_ALBUM &&
                mCurrentItem.getBaseItemType() != BaseItemKind.PLAYLIST
        ) {
            mCurrentItem.refresh(new EmptyResponse() {
                @Override
                public void onResponse() {
                    ItemRowAdapter adapter = (ItemRowAdapter) mCurrentRow.getAdapter();
                    adapter.notifyItemRangeChanged(adapter.indexOf(mCurrentItem), 1);
                }
            });
        }
    }

    private final class SpecialViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item, RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof GridButton) {
                switch (((GridButton) item).getId()) {
                    case GRID:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryBrowser(ModelCompat.asSdk(mFolder)));
                        break;

                    case ALBUMS:
                        mFolder.setDisplayPreferencesId(mFolder.getId() + "AL");
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryBrowser(ModelCompat.asSdk(mFolder), "MusicAlbum"));
                        break;

                    case ARTISTS:
                        mFolder.setDisplayPreferencesId(mFolder.getId() + "AR");

                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryBrowser(ModelCompat.asSdk(mFolder), "AlbumArtist"));
                        break;

                    case BY_LETTER:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryByLetter(ModelCompat.asSdk(mFolder), itemTypeString));
                        break;

                    case GENRES:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryByGenres(ModelCompat.asSdk(mFolder), itemTypeString));
                        break;

                    case SUGGESTED:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.librarySuggestions(ModelCompat.asSdk(mFolder)));
                        break;

                    case FAVSONGS:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.itemList(UUIDSerializerKt.toUUID(ItemListFragment.FAV_SONGS), UUIDSerializerKt.toUUID(mFolder.getId())));
                        break;

                    case SERIES:
                    case LiveTvOption.LIVE_TV_SERIES_OPTION_ID:
                        BaseItemDto seriesTimers = new BaseItemDto();
                        seriesTimers.setId(UUID.randomUUID().toString());
                        seriesTimers.setBaseItemType(BaseItemType.Folder);
                        seriesTimers.setCollectionType("SeriesTimers");
                        seriesTimers.setName(requireContext().getString(R.string.lbl_series_recordings));
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryBrowser(ModelCompat.asSdk(seriesTimers)));
                        break;

                    case SCHEDULE:
                    case LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.getLiveTvSchedule());
                        break;

                    case LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID:
                        BaseItemDto folder = new BaseItemDto();
                        folder.setId(UUID.randomUUID().toString());
                        folder.setBaseItemType(BaseItemType.Folder);
                        folder.setName(requireContext().getString(R.string.lbl_recorded_tv));
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryBrowser(ModelCompat.asSdk(folder)));
                        break;

                    case LiveTvOption.LIVE_TV_GUIDE_OPTION_ID:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.getLiveTvGuide());
                        break;

                    default:
                        Toast.makeText(getActivity(), item.toString() + getString(R.string.msg_not_implemented), Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        }
    }

    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(final Presenter.ViewHolder itemViewHolder, Object item, RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (!(item instanceof BaseRowItem)) return;

            mediaManager.getValue().setCurrentMediaTitle(row.getHeaderItem().getName());
            ItemLauncher.launch((BaseRowItem) item, (ItemRowAdapter) ((ListRow) row).getAdapter(), ((BaseRowItem) item).getIndex(), getActivity());
        }
    }

    private final class ItemViewSelectedListener implements OnItemViewSelectedListener {
        @Override
        public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                   RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof GridButton && ((GridButton) item).getId() == FAVSONGS) {
                // Set to specialized item
                mCurrentItem = favSongsRowItem;
            }

            if (!(item instanceof BaseRowItem)) {
                mTitle.setText(mFolder != null ? mFolder.getName() : "");
                mInfoRow.removeAllViews();
                mSummary.setText("");
                // Fill in default background
                backgroundService.getValue().clearBackgrounds();
                return;
            }

            BaseRowItem rowItem = (BaseRowItem) item;

            mCurrentItem = rowItem;
            mCurrentRow = (ListRow) row;
            mInfoRow.removeAllViews();

            mTitle.setText(rowItem.getName(requireContext()));

            String summary = rowItem.getSummary(requireContext());
            if (summary != null) mSummary.setText(markdownRenderer.getValue().toMarkdownSpanned(summary));
            else mSummary.setText(null);

            InfoLayoutHelper.addInfoRow(requireContext(), rowItem, mInfoRow, true, true);

            ItemRowAdapter adapter = (ItemRowAdapter) ((ListRow) row).getAdapter();
            adapter.loadMoreItemsIfNeeded(rowItem.getIndex());

            backgroundService.getValue().setBackground(rowItem.getBaseItem());
        }
    }
}
