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

import java.util.Arrays;

public interface KacaIndex {

    abstract class Digest implements KacaIndex {
        private final byte[] digest;

        protected Digest(byte[] digest) {
            verifyDigest(digest);

            this.digest = digest;
        }

        public abstract int getDigestLength();

        protected void verifyDigest(byte[] digest) {
            if (digest.length != getDigestLength()) {
                throw new IllegalArgumentException("Invalid digest: " + Arrays.toString(digest));
            }
        }

        public byte[] getDigest() {
            return digest.clone();
        }
    }

    final class SHA256 extends Digest {
        public SHA256(byte[] digest) {
            super(digest);
        }

        @Override
        public int getDigestLength() {
            return 32;
        }
    }
}
