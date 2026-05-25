package daytrader.gateway

interface BrokerAdapter {
    val brokerId: BrokerId

    fun start()

    fun shutdown()
}
