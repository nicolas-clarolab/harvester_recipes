import com.mashape.unirest.http.Unirest
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

@Grapes([
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
@Grab(group='joda-time', module='joda-time', version='2.3')
])
@Slf4j
class CdNetworksDataRecipe {

    def BASE_URL = 'https://openapi.us.cdnetworks.com/'
    def LOGIN_URL = BASE_URL + 'stat/rest/login'
    def API_KEYS_URL = BASE_URL + 'stat/rest/getApiKeyList'
    def BANDWIDTH_URL = BASE_URL + 'stat/rest/traffic/bandwidth'
    def USAGE_URL = BASE_URL + 'stat/rest/traffic/dataTransferred'


    def run(config) {
        try {
            def sessionToken = login(config['cdnetworks_username'], config['cdnetworks_password'])
            def apiKeys = getApiKeys(sessionToken)
            return new JsonBuilder(['usage': usageData(sessionToken, apiKeys), 'bandwidth': bandwidthData(sessionToken, apiKeys)]).toPrettyString()
        } catch (Exception e) {
            return e.getMessage()
        }
    }

    def hit(url) {
        def response = Unirest.get(url).asString()
        if (response && response.code == 200 && response.body) {
            return new JsonSlurper().parseText(response.body)
        } else {
            throw new RuntimeException("Couldn't connect to CDNetworks API")
        }
    }

    def getApiKeys(sessionToken) {
        def url = API_KEYS_URL + '?output=json'
        url += '&sessionToken=' + sessionToken
        def response = hit(url)
        if (response.apiKeyInfo.resultCode == 0) {
            return response.apiKeyInfo.apiKeyInfoItem
        } else {
            throw new RuntimeException("Error getting api keys")
        }
    }

    def login(username, password) {
        def url = LOGIN_URL + '?output=json'
        url += '&user=' + username
        url += '&pass=' + password
        def response = hit(url)
        if (response.loginResponse.resultCode == 0) {
            return response.loginResponse.session.sessionToken
        } else {
            throw new RuntimeException("Bad credentials")
        }
    }

    def bandwidthData(sessionToken, apiKeys) {
        def baseUrl = BANDWIDTH_URL + '?output=json'
        baseUrl += '&sessionToken=' + sessionToken
        def result = [:]
        apiKeys.each { key ->
            def url = baseUrl + "&apiKey=" + key.apiKey
            url += '&timeInterval=0' // 5 minutes
            url += '&fromDate=' + DateTime.now().minusMinutes(5).toString(DateTimeFormat.forPattern("YYYYMMdd"))
            url += '&toDate=' + DateTime.now().toString(DateTimeFormat.forPattern("YYYYMMdd"))
            def response = hit(url)
            def measures = []
            if (response.bandwidthResponse.resultCode == 0) {
                measures = response.bandwidthResponse.bandwidthItem
            } else {
                throw new RuntimeException("Error while getting bandwidth data")
            }

            def bandwidthUsage = measures.collect { measure -> measure.bandwidth }.reverse().find { value -> value > 0 }

            if (bandwidthUsage == null) {
                bandwidthUsage = 0
            }

            result[key.serviceName] = [value: bandwidthUsage / 1048576, unit: 'Mbps']
        }
        return result
    }

    def usageData(sessionToken, apiKeys) {
        def baseUrl = USAGE_URL + '?output=json'
        baseUrl += '&sessionToken=' + sessionToken
        def result = [:]
        apiKeys.each { key ->
            def url = baseUrl + "&apiKey=" + key.apiKey
            url += '&timeInterval=2' // 1 day
            url += '&fromDate=' + DateTime.now().withDayOfMonth(1).withMinuteOfHour(0).toString(DateTimeFormat.forPattern("YYYYMMdd"))
            url += '&toDate=' + DateTime.now().dayOfMonth().withMaximumValue().minuteOfDay().withMaximumValue().toString(DateTimeFormat.forPattern("YYYYMMdd"))
            def response = hit(url)
            def measures = []
            if (response.dataTransferredResponse.resultCode == 0) {
                measures = response.dataTransferredResponse.dataTransferredItem
            } else {
                throw new RuntimeException("Error while getting usage data")
            }

            def usageInBytes = measures.collect { measure ->
                measure.dataTransferred
            }.sum()

            result[key.serviceName] = [value: usageInBytes / (1024 * 1024 * 1024), unit: 'GB']
        }
        return result
    }


}