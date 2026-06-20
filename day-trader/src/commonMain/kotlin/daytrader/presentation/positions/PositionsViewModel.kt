package daytrader.presentation.positions

import daytrader.data.PositionRepository
import daytrader.domain.Position
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.ui.UiCoroutineScopes
import daytrader.presentation.ui.safeUiEmit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class PositionsViewModel(
    private val repository: PositionRepository,
    scope: CoroutineScope = UiCoroutineScopes.forScreen(AppScreen.POSITIONS, "PositionsViewModel"),
) {
    private val scope = scope

    private var positions: List<Position> = emptyList()
    private var sortColumn = SortableColumn.COMPANY
    private var sortDirection = SortDirection.ASCENDING

    private val _uiState = MutableStateFlow(PositionsUiState())
    val uiState: StateFlow<PositionsUiState> = _uiState.asStateFlow()

    init {
        repository.positions
            .onEach { list ->
                positions = list
                emitUiState()
            }
            .launchIn(scope)
    }

    fun onHeaderClick(column: SortableColumn) {
        if (sortColumn == column) {
            sortDirection = if (sortDirection == SortDirection.ASCENDING) {
                SortDirection.DESCENDING
            } else {
                SortDirection.ASCENDING
            }
        } else {
            sortColumn = column
            sortDirection = SortDirection.ASCENDING
        }
        emitUiState()
    }

    private fun emitUiState() {
        safeUiEmit(AppScreen.POSITIONS, "emitUiState") {
            val comparator = when (sortColumn) {
                SortableColumn.COMPANY -> compareBy<Position> { it.companyName }
                SortableColumn.SYMBOL -> compareBy { it.symbol }
                SortableColumn.UNREALIZED_PNL -> compareBy { it.totalUnrealizedPnL }
            }

            val sorted = if (sortDirection == SortDirection.DESCENDING) {
                positions.sortedWith(comparator.reversed())
            } else {
                positions.sortedWith(comparator)
            }

            _uiState.update {
                PositionsUiState(
                    rows = sorted.map(PositionUiMapper::toRowUi),
                    sortColumn = sortColumn,
                    sortDirection = sortDirection
                )
            }
        }
    }
}
