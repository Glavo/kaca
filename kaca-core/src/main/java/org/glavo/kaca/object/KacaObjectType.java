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

import org.glavo.kaca.internal.util.LittleEndian;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public enum KacaObjectType {
    FILE,
    SNAP,
    ;

    private static final Map<Integer, KacaObjectType> map = new HashMap<>();

    static {
        for (KacaObjectType type : values()) {
            map.put(LittleEndian.getInt(type.header, 0), type);
        }
    }

    public static KacaObjectType lookup(byte[] array) {
        return lookup(array, 0);
    }

    public static KacaObjectType lookup(byte[] array, int offset) {
        return map.get(LittleEndian.getInt(array, offset));
    }

    private final byte[] header;

    KacaObjectType() {
        if (name().length() != 4) {
            throw new AssertionError("Invalid header: " + name());
        }
        this.header = name().getBytes(StandardCharsets.US_ASCII);
    }
}
