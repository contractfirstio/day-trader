package daytrader.data.persistence

object AppDataFiles {
    const val DEPLOYMENTS = "deployments.json"
    const val STRATEGIES_SCREEN = "strategies-screen.json"

    /** Pre-terminology-refactor format (`instances` + `performance` keys). */
    const val LEGACY_INSTANCES_JSON = "instances.json"
    const val LEGACY_STRATEGY_INSTANCES = "strategy-instances.json"
    const val LEGACY_STRATEGIES_APP_STATE = "app-state.json"
}
