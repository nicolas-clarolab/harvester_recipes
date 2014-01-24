import com.mashape.unirest.http.utils.Base64Coder
import groovy.json.JsonSlurper
import org.apache.commons.io.IOUtils
import org.testng.annotations.Test

/**
 * Created by ben on 1/23/14.
 */
@Test
class TestRecipes {

    @Test
    def void testLevel3() {
        do_test("level3")
    }

    @Test
    def void testLimeLight() {
        do_test("limelight")
    }

    @Test
    def void testCdnetworks() {
        do_test("cdnetworks")
    }

    def do_test(name) {
        def creds = new JsonSlurper().parseText(new File("./src/test/resources/creds.json").text)

        def script = Base64Coder.encodeString(new File("./src/main/groovy/${name}.groovy").text)

        def config = [
                script: script
        ]

        config += creds[name]

        def result = execute_groovy_task(config)

        println result

        assert result != null
        assert new JsonSlurper().parseText(result) != null
    }

    def execute_groovy_task(config) {
        ClassLoader classLoader = getClass().getClassLoader()
        GroovyClassLoader loader = new GroovyClassLoader(classLoader)

        System.setProperty("groovy.grape.report.downloads", "true")
        //System.setProperty("ivy.cache.ttl.default", "1d")

        if (!new File("./grapeConfig.xml").exists()) {
            def grapeConfig = IOUtils.toString(classLoader.getResourceAsStream("grapeConfig.xml") as InputStream)
            new File("./grapeConfig.xml").write(grapeConfig)
        }

        System.setProperty("grape.config", "./grapeConfig.xml")

        groovy.grape.Grape.enableAutoDownload = true
        groovy.grape.Grape.enableGrapes = true

        Class groovyClass = loader.parseClass(Base64Coder.decodeString(config.script) as String)

        GroovyObject groovyObject = (GroovyObject) groovyClass.newInstance()

        def result =  groovyObject.invokeMethod("run", config)

        result
    }

}
