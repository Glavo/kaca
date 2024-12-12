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

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

public final class LocalKacaRepository extends KacaRepository {

    private final Path repositoryPath;
    private final FileChannel lockFileChannel;

    private LocalKacaRepository(boolean isReadOnly, KacaIndexType indexType,
                                Path repositoryPath, FileChannel lockFileChannel) {
        super(isReadOnly, indexType);
        this.repositoryPath = repositoryPath;
        this.lockFileChannel = lockFileChannel;
    }

    public Path getRepositoryPath() {
        return repositoryPath;
    }

    @Override
    public KacaIndex putObject(KacaObjectType type, KacaObjectOptions options, InputStream data) {
        checkWritable();



        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    public void close() throws IOException {
        lockFileChannel.close();
    }
}
