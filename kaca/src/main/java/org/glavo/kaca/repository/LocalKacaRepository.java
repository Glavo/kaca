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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

public abstract class LocalKacaRepository extends KacaRepository { // TODO

    private final Path repositoryPath;
    private final FileChannel lockFileChannel;

    private LocalKacaRepository(boolean isReadOnly, Path repositoryPath, FileChannel lockFileChannel) {
        super(isReadOnly);
        this.repositoryPath = repositoryPath;
        this.lockFileChannel = lockFileChannel;
    }

    public Path getRepositoryPath() {
        return repositoryPath;
    }

    @Override
    public void close() throws IOException {
        lockFileChannel.close();
    }
}
