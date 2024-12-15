/*
 * Copyright 2024 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.kaca.index;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class DigestKacaIndexType extends KacaIndexType {

    public static final DigestKacaIndexType SHA256 = new DigestKacaIndexType("SHA-256", 32);
    public static final DigestKacaIndexType SHA512 = new DigestKacaIndexType("SHA-512", 64);

    private final String name;
    private final int digestLength;

    private DigestKacaIndexType(String name, int digestLength) {
        this.name = name;
        this.digestLength = digestLength;
    }

    public String getName() {
        return name;
    }

    public int getDigestLength() {
        return digestLength;
    }

    @Override
    public KacaIndexBuilder newBuilder() {
        MessageDigest digest;

        try {
            digest = MessageDigest.getInstance(name);
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError(e);
        }

        return new KacaIndexBuilder() {

            @Override
            public void update(byte[] data, int offset, int length) {
                digest.update(data, offset, length);
            }

            @Override
            public KacaIndex build() {
                return new DigestKacaIndex(DigestKacaIndexType.this, digest.digest());
            }
        };
    }

    @Override
    public int hashCode() {
        return DigestKacaIndexType.class.hashCode() ^ name.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DigestKacaIndexType && this.name.equals(((DigestKacaIndexType) obj).name);
    }
}
