package com.dking.crocapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodeParserTest {

    @Test
    fun parseCode_directCode_returnsNormalizedCode() {
        assertEquals("1234-test", QrCodeParser.parseCode("1234-test"))
        assertEquals("hello-world-code", QrCodeParser.parseCode("hello world code"))
        assertEquals("simplecode", QrCodeParser.parseCode("simplecode"))
    }

    @Test
    fun parseCode_crocScheme_extractsCode() {
        assertEquals("1234-test", QrCodeParser.parseCode("croc://receive?code=1234-test"))
        assertEquals("7742-code-name", QrCodeParser.parseCode("croc://7742-code-name"))
        assertEquals("1234-test", QrCodeParser.parseCode("croc://receive/1234-test"))
    }

    @Test
    fun parseCode_httpsGetCrocUrl_extractsCode() {
        assertEquals("1234-test", QrCodeParser.parseCode("https://getcroc.com/?code=1234-test"))
        assertEquals("7742-code-name", QrCodeParser.parseCode("https://getcroc.com/?code=7742-code-name"))
    }

    @Test
    fun parseCode_httpUrlAndNoProtocol_extractsCode() {
        assertEquals("1234-test", QrCodeParser.parseCode("http://getcroc.com/?code=1234-test"))
        assertEquals("1234-test", QrCodeParser.parseCode("getcroc.com/?code=1234-test"))
    }

    @Test
    fun parseCode_multipleQueryParams_extractsCode() {
        assertEquals("my-code", QrCodeParser.parseCode("https://getcroc.com/?foo=bar&code=my-code&baz=123"))
        assertEquals("my-code", QrCodeParser.parseCode("https://getcroc.com/?code=my-code&baz=123"))
    }

    @Test
    fun parseCode_urlEncodedCode_decodesAndNormalizes() {
        assertEquals("my-secret-code", QrCodeParser.parseCode("https://getcroc.com/?code=my%20secret%20code"))
        assertEquals("my-code-123", QrCodeParser.parseCode("https://getcroc.com/?code=my+code+123"))
        assertEquals("my-code-123", QrCodeParser.parseCode("croc://receive?code=my%20code%20123"))
    }

    @Test
    fun parseCode_whitespaceHandling_trimsWhitespace() {
        assertEquals("1234-test", QrCodeParser.parseCode("  1234-test  "))
        assertEquals("1234-test", QrCodeParser.parseCode("  https://getcroc.com/?code=1234-test  "))
        assertEquals("1234-test", QrCodeParser.parseCode("  croc://receive?code=1234-test  "))
    }

    @Test
    fun parseCode_emptyOrBlank_returnsEmptyString() {
        assertEquals("", QrCodeParser.parseCode(""))
        assertEquals("", QrCodeParser.parseCode("   "))
    }

    @Test
    fun parseCode_caseInsensitiveCodeParam_extractsCode() {
        assertEquals("TEST-CODE", QrCodeParser.parseCode("https://getcroc.com/?CODE=TEST-CODE"))
        assertEquals("TEST-CODE", QrCodeParser.parseCode("croc://receive?CODE=TEST-CODE"))
    }

    @Test
    fun receiveDeepLink_formatsCorrectly() {
        assertEquals("croc://receive?code=1234-test", QrCodeParser.receiveDeepLink("1234-test"))
    }
}
