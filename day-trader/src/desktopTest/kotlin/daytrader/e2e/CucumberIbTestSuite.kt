package daytrader.e2e

import io.cucumber.junit.Cucumber
import io.cucumber.junit.CucumberOptions
import org.junit.runner.RunWith

@RunWith(Cucumber::class)
@CucumberOptions(
    features = ["classpath:features"],
    glue = ["daytrader.e2e.steps"],
    plugin = ["pretty", "summary"],
    tags = "@ib and not @ignored and not @wip",
)
class CucumberIbTestSuite
