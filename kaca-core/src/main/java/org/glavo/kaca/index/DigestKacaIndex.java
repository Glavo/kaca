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

import org.jetbrains.annotations.ApiStatus;

public final class DigestKacaIndex extends KacaIndex {
    private final DigestKacaIndexType type;

    private final long length;
    private final byte[] digest;

    DigestKacaIndex(DigestKacaIndexType type, long length, byte[] digest) {
        this.length = length;
        assert digest.length == type.getDigestLength();

        this.type = type;
        this.digest = digest;
    }

    @Override
    public DigestKacaIndexType getType() {
        return type;
    }

    public long getLength() {
        return length;
    }

    public byte[] getDigest() {
        return digest.clone();
    }

    @ApiStatus.Internal
    public byte[] getDigestNoCopy() {
        return digest;
    }
}
