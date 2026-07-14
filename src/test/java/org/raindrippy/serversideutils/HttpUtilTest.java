package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure-logic tests for {@link HttpUtil#cookiesToHeader} and the {@link HttpUtil.HttpResponse} value class. */
class HttpUtilTest {

    @Test
    @DisplayName("null cookie list -> null")
    void nullList() {
        assertNull(HttpUtil.cookiesToHeader(null));
    }

    @Test
    @DisplayName("empty cookie list -> null")
    void emptyList() {
        assertNull(HttpUtil.cookiesToHeader(Collections.emptyList()));
    }

    @Test
    @DisplayName("single Set-Cookie keeps only the name=value pair")
    void singleCookieStripsAttributes() {
        List<String> setCookies = Collections.singletonList("userToken=abc; Path=/; HttpOnly");
        assertEquals("userToken=abc", HttpUtil.cookiesToHeader(setCookies));
    }

    @Test
    @DisplayName("multiple cookies are joined with '; '")
    void multipleCookiesJoined() {
        List<String> setCookies = Arrays.asList(
                "userToken=abc; Path=/; HttpOnly",
                "session=xyz; Secure");
        assertEquals("userToken=abc; session=xyz", HttpUtil.cookiesToHeader(setCookies));
    }

    @Test
    @DisplayName("a cookie value with no attributes (no ';') is used whole")
    void cookieWithoutSemicolon() {
        assertEquals("userToken=abc", HttpUtil.cookiesToHeader(Collections.singletonList("userToken=abc")));
    }

    @Test
    @DisplayName("null / empty / whitespace entries are skipped")
    void skipsBlankEntries() {
        List<String> setCookies = Arrays.asList(null, "", "   ", "userToken=abc; Path=/");
        assertEquals("userToken=abc", HttpUtil.cookiesToHeader(setCookies));
    }

    @Test
    @DisplayName("a list of only blank entries -> null")
    void allBlankEntries() {
        assertNull(HttpUtil.cookiesToHeader(Arrays.asList("", "   ", ";")));
    }

    @Test
    @DisplayName("HttpResponse two-arg constructor leaves setCookies null")
    void httpResponseTwoArg() {
        HttpUtil.HttpResponse res = new HttpUtil.HttpResponse(200, "body");
        assertEquals(200, res.statusCode);
        assertEquals("body", res.body);
        assertNull(res.setCookies);
    }

    @Test
    @DisplayName("HttpResponse three-arg constructor wires all fields")
    void httpResponseThreeArg() {
        List<String> cookies = Collections.singletonList("a=b");
        HttpUtil.HttpResponse res = new HttpUtil.HttpResponse(404, "err", cookies);
        assertEquals(404, res.statusCode);
        assertEquals("err", res.body);
        assertEquals(cookies, res.setCookies);
    }
}
