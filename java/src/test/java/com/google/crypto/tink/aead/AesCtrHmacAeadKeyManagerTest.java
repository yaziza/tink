// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink.aead;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeyTypeManager;
import com.google.crypto.tink.proto.AesCtrHmacAeadKey;
import com.google.crypto.tink.proto.AesCtrHmacAeadKeyFormat;
import com.google.crypto.tink.proto.AesCtrKeyFormat;
import com.google.crypto.tink.proto.AesCtrParams;
import com.google.crypto.tink.proto.HashType;
import com.google.crypto.tink.proto.HmacKeyFormat;
import com.google.crypto.tink.proto.HmacParams;
import com.google.crypto.tink.proto.KeyData.KeyMaterialType;
import com.google.crypto.tink.subtle.EncryptThenAuthenticate;
import com.google.crypto.tink.subtle.Random;
import com.google.crypto.tink.testing.TestUtil;
import java.security.GeneralSecurityException;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for AesCtrHmaAeadKeyManager. */
@RunWith(JUnit4.class)
public class AesCtrHmacAeadKeyManagerTest {
  private final AesCtrHmacAeadKeyManager manager = new AesCtrHmacAeadKeyManager();
  private final KeyTypeManager.KeyFactory<AesCtrHmacAeadKeyFormat, AesCtrHmacAeadKey> factory =
      manager.keyFactory();

  @Test
  public void basics() throws Exception {
    assertThat(manager.getKeyType())
        .isEqualTo("type.googleapis.com/google.crypto.tink.AesCtrHmacAeadKey");
    assertThat(manager.getVersion()).isEqualTo(0);
    assertThat(manager.keyMaterialType()).isEqualTo(KeyMaterialType.SYMMETRIC);
  }

  @Test
  public void validateKeyFormat_empty() throws Exception {
    try {
      factory.validateKeyFormat(AesCtrHmacAeadKeyFormat.getDefaultInstance());
      fail();
    } catch (GeneralSecurityException e) {
      // expected.
    }
  }


  // Returns an AesCtrKeyFormat.Builder with valid parameters
  private static AesCtrKeyFormat.Builder createAesCtrKeyFormat() {
    return AesCtrKeyFormat.newBuilder()
        .setParams(AesCtrParams.newBuilder().setIvSize(16))
        .setKeySize(16);
  }

  // Returns an HmacParams.Builder with valid parameters
  private static HmacParams.Builder createHmacParams() {
    return HmacParams.newBuilder().setHash(HashType.SHA256).setTagSize(32);
  }

  // Returns an HmacParams.Builder with valid parameters
  private static HmacKeyFormat.Builder createHmacKeyFormat() {
    return HmacKeyFormat.newBuilder().setParams(createHmacParams()).setKeySize(32);
  }

  // Returns an AesCtrHmacStreamingKeyFormat.Builder with valid parameters
  private static AesCtrHmacAeadKeyFormat.Builder createKeyFormat() {
    return AesCtrHmacAeadKeyFormat.newBuilder()
        .setAesCtrKeyFormat(createAesCtrKeyFormat())
        .setHmacKeyFormat(createHmacKeyFormat());
  }

  @Test
  public void validateKeyFormat_valid() throws Exception {
    factory.validateKeyFormat(createKeyFormat().build());
  }

  @Test
  public void validateKeyFormat_keySizes() throws Exception {
    for (int keySize = 0; keySize < 42; ++keySize) {
      AesCtrHmacAeadKeyFormat format =
          createKeyFormat().setAesCtrKeyFormat(createAesCtrKeyFormat().setKeySize(keySize)).build();
      if (keySize == 16 || keySize == 32) {
        factory.validateKeyFormat(format);
      } else {
        try {
          factory.validateKeyFormat(format);
          fail();
        } catch (GeneralSecurityException e) {
          // expected
        }
      }
    }
  }

  @Test
  public void validateKeyFormat_hmacKeySizes() throws Exception {
    for (int keySize = 0; keySize < 42; ++keySize) {
      AesCtrHmacAeadKeyFormat format =
          createKeyFormat().setHmacKeyFormat(createHmacKeyFormat().setKeySize(keySize)).build();
      if (keySize >= 16) {
        factory.validateKeyFormat(format);
      } else {
        try {
          factory.validateKeyFormat(format);
          fail("For key size" + keySize);
        } catch (GeneralSecurityException e) {
          // expected
        }
      }
    }
  }

  @Test
  public void createKey_multipleTimes_distinctAesKeys() throws Exception {
    AesCtrHmacAeadKeyFormat format = createKeyFormat().build();
    Set<String> keys = new TreeSet<>();
    // Calls newKey multiple times and make sure that they generate different keys.
    int numTests = 50;
    for (int i = 0; i < numTests; i++) {
      keys.add(
          TestUtil.hexEncode(factory.createKey(format).getAesCtrKey().getKeyValue().toByteArray()));
    }
    assertThat(keys).hasSize(numTests);
  }

  @Test
  public void createKey_multipleTimes_distinctHmacKeys() throws Exception {
    AesCtrHmacAeadKeyFormat format = createKeyFormat().build();
    Set<String> keys = new TreeSet<>();
    // Calls newKey multiple times and make sure that they generate different keys.
    int numTests = 50;
    for (int i = 0; i < numTests; i++) {
      keys.add(
          TestUtil.hexEncode(factory.createKey(format).getHmacKey().getKeyValue().toByteArray()));
    }
    assertThat(keys).hasSize(numTests);
  }

  @Test
  public void getPrimitive() throws Exception {
    AesCtrHmacAeadKey key =
        factory.createKey(
            createKeyFormat()
                .setHmacKeyFormat(
                    createHmacKeyFormat().setParams(createHmacParams().setHash(HashType.SHA512)))
                .build());
    Aead managerAead = manager.getPrimitive(key, Aead.class);
    Aead directAead =
        EncryptThenAuthenticate.newAesCtrHmac(
            key.getAesCtrKey().getKeyValue().toByteArray(),
            key.getAesCtrKey().getParams().getIvSize(),
            "HMACSHA512",
            key.getHmacKey().getKeyValue().toByteArray(),
            key.getHmacKey().getParams().getTagSize());

    byte[] plaintext = Random.randBytes(20);
    byte[] associatedData = Random.randBytes(20);
    assertThat(directAead.decrypt(managerAead.encrypt(plaintext, associatedData), associatedData))
        .isEqualTo(plaintext);
  }
}
