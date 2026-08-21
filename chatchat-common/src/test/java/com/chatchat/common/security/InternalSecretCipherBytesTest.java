package com.chatchat.common.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InternalSecretCipherBytesTest {
    @Test void binaryEnvelopeRoundTripsAndRejectsTampering(){
        byte[] source=new byte[]{0,1,2,10,13,-1,100};byte[] encrypted=InternalSecretCipher.encryptBytes(source,"test-key");
        assertThat(encrypted).isNotEqualTo(source);assertThat(InternalSecretCipher.decryptBytes(encrypted,"test-key")).isEqualTo(source);
        encrypted[encrypted.length-1]^=1;assertThatThrownBy(()->InternalSecretCipher.decryptBytes(encrypted,"test-key")).isInstanceOf(IllegalArgumentException.class);
    }
}
