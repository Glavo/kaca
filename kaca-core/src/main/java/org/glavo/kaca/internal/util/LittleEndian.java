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
package org.glavo.kaca.internal.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class LittleEndian {

    public static short getShort(byte[] array, int offset) {
        return ByteBuffer.wrap(array).order(ByteOrder.LITTLE_ENDIAN).getShort(offset);
    }

    public static int getInt(byte[] array, int offset) {
        return ByteBuffer.wrap(array).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
    }

    public static long getLong(byte[] array, int offset) {
        return ByteBuffer.wrap(array).order(ByteOrder.LITTLE_ENDIAN).getLong(offset);
    }

    private LittleEndian() {
    }
}
