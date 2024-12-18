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
package org.glavo.kaca.object;

public final class KacaObjectOptions {

    private final KacaCompressMethod compressMethod;
    private final int compressLevel;

    private KacaObjectOptions(KacaCompressMethod compressMethod, int compressLevel) {
        this.compressMethod = compressMethod;
        this.compressLevel = compressLevel;
    }

    public KacaCompressMethod getCompressMethod() {
        return compressMethod;
    }

    public int getCompressLevel() {
        return compressLevel;
    }
}
