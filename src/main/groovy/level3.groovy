import com.mashape.unirest.http.Unirest
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat

@Grapes([
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
@Grab(group = 'joda-time', module = 'joda-time', version = '2.3')
])
@Slf4j
class Level3 {

    def LEVEL3_API_HOST = "https://ws.level3.com"

    def config = [:]

    def run(config) {

        this.config = config

        if ((config as Map).containsKey("level3_access_key") &&
                (config as Map).containsKey("level3_secret_key")) {

            def ag = access_group()

            def payload = [:]

            get_services(ag).each { service ->
                def current_month = DateTime.now(DateTimeZone.UTC).toString(DateTimeFormat.forPattern("YYYYMM"))

                if (service.key == "CACHING") {
                    String baseUrl = "/usage/cdn/v1.0/" + ag;
                    def xml = Unirest.get("$LEVEL3_API_HOST$baseUrl").fields([
                            serviceType: service.key as String,
                            dateMonth: current_month as String]).headers(headers(baseUrl)).asString().body

                    def response = new XmlSlurper().parseText(xml)

                    payload["total_monthly"] = response.summaryData.'*'.collect { ["${it.name()}": it.text()] }.sum()

                    response.services.service.networkIdentifiers.ni.each { ni ->
                        def identifier = ni.serviceResource.text()
                        payload[identifier] = ni.summaryData.'*'.collect { ["${it.name()}": it.text()] }.sum()
                    }
                }
            }

            return new JsonBuilder(payload).toPrettyString()

        } else {
            log.error("missing level3 arguments")
        }

    }

    def get_services(ag) {
        String baseUrl = "/services/cdn/v1.0/" + ag
        def xml = Unirest.get("$LEVEL3_API_HOST$baseUrl").headers(headers(baseUrl)).asString().body
        def response = new XmlSlurper().parseText(xml)

        return response.services.service.collect {
            def keys = it.networkIdentifiers.ni.findAll { it.active.text() == "Y" }.collect {
                it.serviceResource.text()
            }
            def product = it.product.text()
            ["$product": keys]
        }.sum()

    }

    /**
     * Returns details of current credit balance for API key and date of next top-up
     * that includes the access group, which is what we want
     * See https://mediaportal.level3.com/cdnServices/help/Content/API/API_Specs/Key.htm
     *
     * @return
     */
    def access_group() {
        String baseUrl = "/key/v1.0";
        def response = Unirest.get("$LEVEL3_API_HOST$baseUrl").headers(headers(baseUrl)).asString()

        if (response.code == 200) {
            def xml = response.body
            def payload = new XmlSlurper().parseText(xml)
            return payload.assignedAccessGroup.@id.text()
        } else {
            throw new RuntimeException("${new JsonSlurper().parseText(response.body).error.message}")
        }
    }

    /**
     * A lot of level3 specific voodoo in order to authenticate
     * See https://mediaportal.level3.com/cdnServices/help/Content/API/API_KeysSecurity.htm
     *
     * @param baseUrl
     * @return
     */
    def headers(String baseUrl) {
        def Map<String, String> headers = [:]

        String key = this.config.level3_access_key
        String secretKey = this.config.level3_secret_key
        String method = "GET";
        String contentType = "text/xml";
        String date = getDateHeader(new Date(System.currentTimeMillis()));
        String contentMD5 = "";

        // Generate signature
        StringBuffer buf = new StringBuffer();
        buf.append(date).append("\n");
        buf.append(baseUrl).append("\n");
        buf.append(contentType).append("\n");
        buf.append(method).append("\n");
        buf.append(contentMD5);
        String signature = sign(buf.toString(), secretKey);

        headers.put("Date", date);
        headers.put("Content-Type", contentType);
        headers.put("Content-MD5", contentMD5);
        headers.put("Authorization", "MPA " + key + ":" + signature);

        return headers;
    }

    def getDateHeader(Date targetDate) {
        String dateFormat = "EEE, dd MMM yyyy HH:mm:ss ";
        SimpleDateFormat sdf = new SimpleDateFormat(dateFormat, Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(targetDate) + "GMT";
    }

    def sign(String data, String secretKey) throws NoSuchAlgorithmException, UnsupportedEncodingException, InvalidKeyException {
        javax.crypto.Mac mac = Mac.getInstance("HmacSHA1");
        byte[] keyBytes = secretKey.getBytes("UTF8");
        javax.crypto.spec.SecretKeySpec signingKey = new SecretKeySpec(keyBytes, "HmacSHA1");
        mac.init(signingKey);
        byte[] signBytes = mac.doFinal(data.getBytes("UTF8"));

        // Use any base64 encoder.  This is using apache-codec-1.4
        String base64 = new String(org.apache.commons.codec.binary.Base64.encodeBase64(signBytes));
        if (base64.endsWith("\r\n"))
            base64 = base64.substring(0, base64.length() - 2);

        return base64;
    }

    def auth(config) {
        this.config = config
        access_group()
    }

    def recipe_config() {
        [
                name: "Level3",
                description: "Level3 CDN bandwidth and usage metrics",
                fields:
                        [
                                ["name": "level3_access_key", "displayName": "API Key ID", "fieldType": "text"],
                                ["name": "level3_secret_key", "displayName": "API Secret Key", "fieldType": "text"],
                        ],
                screens:
                        [
                                [
                                        header: "Enter your Level3 Credentials",
                                        fields: ["level3_access_key", "level3_secret_key"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }

}