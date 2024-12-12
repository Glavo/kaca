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
package org.glavo.kaca.repository;

import org.glavo.kaca.index.KacaIndex;
import org.glavo.kaca.index.KacaIndexType;
import org.glavo.kaca.object.KacaObjectOptions;
import org.glavo.kaca.object.KacaObjectType;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.InputStream;

public abstract class KacaRepository implements Closeable {

    private final boolean isReadOnly;
    private final KacaIndexType indexType;

    protected KacaRepository(boolean isReadOnly, KacaIndexType indexType) {
        this.isReadOnly = isReadOnly;
        this.indexType = indexType;
    }

    protected void checkWritable() throws ReadOnlyKacaRepositoryException {
        if (isReadOnly()) {
            throw new ReadOnlyKacaRepositoryException("This repository is readonly");
        }
    }

    public boolean isReadOnly() {
        return isReadOnly;
    }

    public KacaIndexType getIndexType() {
        return indexType;
    }

    //region KacaObjects

    public abstract KacaIndex putObject(KacaObjectType type, KacaObjectOptions options, InputStream data);

    public KacaIndex putObject(KacaObjectType type, KacaObjectOptions options, byte[] data) {
        return putObject(type, options, new ByteArrayInputStream(data));
    }

    //endregion
}
