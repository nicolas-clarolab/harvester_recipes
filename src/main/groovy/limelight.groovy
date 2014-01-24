import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.commons.codec.DecoderException
import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.IOUtils
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.utils.URIBuilder
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.ISODateTimeFormat

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException

@Grapes([
@Grab(group = 'commons-codec', module = 'commons-codec', version = '1.9'),
@Grab(group = 'org.apache.httpcomponents', module = 'httpclient', version = '4.3.2'),
@Grab(group='commons-io', module='commons-io', version='2.4'),
@Grab(group='joda-time', module='joda-time', version='2.3')

])
@Slf4j
class Limelight {

    def REST_URL = 'https://control.llnw.com/traffic-reporting-api/v2/usage'
    def httpClient

    def run(config) {
        def id = config['username']
        def secret = config['api_shared_key']
        try {
            return new JsonBuilder(['usage': usageData(id, secret), 'bandwidth': bandwidthData(id, secret)]).toPrettyString()
        } catch (Exception e) {
            return e.getMessage()
        }
    }

    def usageData(id, secret) {
        def result = [:]
        def startDate = DateTime.now().withDayOfMonth(1).toString(DateTimeFormat.forPattern("YYYY-MM-dd"))
        def endDate = DateTime.now().toString(DateTimeFormat.forPattern("YYYY-MM-dd"))

        def params = [shortname: id, service: 'http,https', reportDuration: 'custom', endDate: endDate, startDate: startDate, sampleSize: 'daily']
        def responses = getReport(id, secret, REST_URL, params);

        responses.responseItems.each { response ->
            def measures = response['measure']
            //calculate the total values for each measure
            def total = measures.inject([requests: 0, connections: 0, totalbits: 0, inbits: 0, outbits: 0]) { total, current ->
                current.each { key, value ->
                    total[key] += current[key] * 86400 //seconds in a day
                }
                total
            }
            def value = [:]
            value['requests'] = [value: Math.round(total['requests']), unit: 'requests']
            value['connections'] = [value: Math.round(total['connections']), unit: 'connections']
            value['total'] = [value: total['totalbits'] / 8000000, unit: 'MegaBytes'] //this is the way the conversion is done in limelight dashboard
            value['ingress'] = [value: total['inbits'] / 8000000, unit: 'MegaBytes']
            value['egress'] = [value: total['outbits'] / 8000000, unit: 'MegaBytes']
            result[response['service']] = value
        }
        return result
    }

    def bandwidthData(id, secret) {
        def result = [:]
        def startDate = DateTime.now().minusDays(1).toString(ISODateTimeFormat.dateTime())
        def endDate = DateTime.now().minusHours(8).toString(ISODateTimeFormat.dateTime())
        def params = [shortname: id, service: 'http,https', reportDuration: 'custom', sampleSize: 'hourly', startDate: startDate, endDate: endDate, doNotEraseHours: true]
        def responses = getReport(id, secret, REST_URL, params);

        responses['responseItems'].each { response ->
            def measures = response['measure']
            def reqs_per_sec = 0
            def conn_per_sec = 0
            def total_bits_per_sec = 0
            def inbits_per_sec = 0
            def outbits_per_sec = 0
            if (!measures.isEmpty()) {
                def lastMeasure = measures.get(measures.size() - 1)
                reqs_per_sec = lastMeasure['requests']
                conn_per_sec = lastMeasure['connections']
                total_bits_per_sec = lastMeasure['totalbits'] / 1000000
                //this is the way the conversion is done in limelight dashboard
                inbits_per_sec = lastMeasure['inbits'] / 1000000
                outbits_per_sec = lastMeasure['outbits'] / 1000000
            }
            def value = [:]
            value['reqs_per_sec'] = [value: reqs_per_sec, unit: 'rps']
            value['conn_per_sec'] = [value: conn_per_sec, unit: 'cps']
            value['total_bits_per_sec'] = [value: total_bits_per_sec, unit: 'Mbps']
            value['inbits_per_sec'] = [value: inbits_per_sec, unit: 'Mbps']
            value['outbits_per_sec'] = [value: outbits_per_sec, unit: 'Mbps']
            result[response['service']] = value
        }
        return result
    }

    def getReport(id, secret, url, params = [:]) {
        Long timestamp = System.currentTimeMillis();
        HttpGet request = new HttpGet(url as String)
        URIBuilder uri = new URIBuilder(new URI(url as String))
        if (params) {
            params.entrySet().each { param ->
                uri.addParameter(param.key as String, param.value as String)
            }
        }
        request.setURI(uri.build())
        request.setHeader("Content-Type", "application/json")
        request.setHeader("X-LLNW-Security-Principal", id)
        request.setHeader("X-LLNW-Security-Timestamp", timestamp.toString())
        def hmac = generateMac("GET", uri.build().toString(), null, secret, timestamp)
        request.setHeader("X-LLNW-Security-Token", hmac)
        HttpResponse response = http_client().execute(request)
        if (response) {
            def content = IOUtils.toString(response.getEntity().getContent())
            switch (response.getStatusLine().statusCode) {
                case 200:
                    return new JsonSlurper().parseText(content)
                    break;
                default:
                    throw new Exception("Error while trying to connect to Limelight servers")
            }
        }
        null
    }

    def generateMac(httpMethod, String url, body, secret, timestamp) throws NoSuchAlgorithmException, InvalidKeyException, IllegalStateException, UnsupportedEncodingException, DecoderException {
        def decodedSharedKey = Hex.decodeHex(secret.toCharArray());
        def dataString = httpMethod.toUpperCase() + url.replaceFirst('\\?', '') + timestamp;

        if (body != null) {
            dataString = dataString + body;
        }
        SecretKeySpec keySpec = new SecretKeySpec(decodedSharedKey, "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.reset();
        mac.init(keySpec);
        byte[] bytes = mac.doFinal(dataString.getBytes("UTF-8"));
        Formatter formatter = new Formatter()
        bytes.each { b ->
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    def http_client() {
        if (httpClient) {
            return httpClient
        }
        httpClient = HttpClientBuilder.create().build()
        return httpClient
    }

    def auth(config) {
        try {
            def id = config['username']
            def secret = config['api_shared_key']

            def startDate = DateTime.now().withDayOfMonth(1).toString(DateTimeFormat.forPattern("YYYY-MM-dd"))
            def endDate = DateTime.now().toString(DateTimeFormat.forPattern("YYYY-MM-dd"))

            def params = [shortname: id, service: 'http,https', reportDuration: 'custom', endDate: endDate, startDate: startDate, sampleSize: 'daily']
            def responses = getReport(id, secret, REST_URL, params);

        } catch(Exception e) {
            throw new RuntimeException("$e.message")
        }
    }


    def recipe_config() {
        [
                name: "Limelight",
                description: "Limelight CDN bandwidth and usage metrics",
                fields:
                        [
                                ["name": "username", "displayName": "Username", "fieldType": "text"],
                                ["name": "api_shared_key", "displayName": "API Shared Key", "fieldType": "text"],
                        ],
                screens:
                        [
                                [
                                        header: "Enter your Limelight Credentials",
                                        fields: ["username", "api_shared_key"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }

}