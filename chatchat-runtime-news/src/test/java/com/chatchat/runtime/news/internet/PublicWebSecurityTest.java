package com.chatchat.runtime.news.internet;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class PublicWebSecurityTest {
    @Test void deniesSpecialAddressesIncludingMetadataAndIpv6() throws Exception {
        for(String address:List.of("0.0.0.0","127.0.0.1","10.1.2.3","172.16.0.1","192.168.1.1","169.254.169.254","100.100.100.200","198.18.0.1","192.0.2.3","198.51.100.1","203.0.113.1","224.0.0.1","::1","fe80::1","fc00::1","::ffff:127.0.0.1","2002:7f00:1::1","2001:db8::1"))
            assertThat(PublicWebHttp.publicAddress(InetAddress.getByName(address))).as(address).isFalse();
        for(String address:List.of("1.1.1.1","8.8.8.8","192.2.1.1","2606:4700:4700::1111"))
            assertThat(PublicWebHttp.publicAddress(InetAddress.getByName(address))).as(address).isTrue();
    }
    @Test void rejectsNonWebSchemesCredentialsAndNonStandardPorts(){
        for(String url:List.of("file:///etc/passwd","gopher://example.com/","https://user:pass@example.com/","http://example.com:8080/","https://example.com./","https://example.com/#secret","http://2130706433:5221/"))
            assertThatThrownBy(()->PublicWebHttp.validate(url)).isInstanceOf(PublicWebHttp.Rejected.class);
    }
    @Test void rejectsMixedDnsAnswersBeforeConnecting() {
        var http=new PublicWebHttp(){@Override protected InetAddress[] resolve(String host,int millis){try{return new InetAddress[]{InetAddress.getByName("1.1.1.1"),InetAddress.getByName("127.0.0.1")};}catch(Exception e){throw new AssertionError(e);}}};
        assertThatThrownBy(()->http.request(URI.create("https://example.com/"),"GET",Map.of(),null,System.nanoTime()+TimeUnit.SECONDS.toNanos(2)))
            .isInstanceOf(PublicWebHttp.Rejected.class).hasMessage("SSRF_TARGET_DENIED");
    }
    @Test void revalidatesRedirectAndNeverDowngradesTls(){
        var http=new PublicWebHttp(){@Override public Response request(URI uri,String method,Map<String,String> headers,byte[] body,long deadline){return new Response(uri,302,"text/html","http://example.com/plain",new byte[0]);}};
        assertThatThrownBy(()->http.get("https://example.com/",System.nanoTime()+TimeUnit.SECONDS.toNanos(2))).hasMessage("REDIRECT_DOWNGRADE");
    }
    @Test void enforcesOverallExpiredDeadline(){
        assertThatThrownBy(()->PublicWebHttp.remaining(System.nanoTime()-1)).hasMessage("HTTP_TIMEOUT");
    }
    @Test void respectsRobotsSpecificGroupsLongestRulesAndEncodedPaths(){
        String robots="User-agent: *\nDisallow: /\nUser-agent: RuntimeOS-Research\nDisallow: /private\nAllow: /private/public\nDisallow: /*.pdf$\n";
        assertThat(RobotsPolicy.allowed(robots,URI.create("https://example.com/news"))).isTrue();
        assertThat(RobotsPolicy.allowed(robots,URI.create("https://example.com/%70rivate/a"))).isFalse();
        assertThat(RobotsPolicy.allowed(robots,URI.create("https://example.com/private/public/a"))).isTrue();
        assertThat(RobotsPolicy.allowed(robots,URI.create("https://example.com/x.pdf"))).isFalse();
        assertThat(RobotsPolicy.allowed("User-agent: *\nDisallow:\nUser-agent: OtherBot\nDisallow: /",URI.create("https://example.com/a"))).isTrue();
    }
}
