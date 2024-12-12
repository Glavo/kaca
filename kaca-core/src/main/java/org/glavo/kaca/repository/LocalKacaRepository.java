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

import org.glavo.kaca.index.DigestKacaIndex;
import org.glavo.kaca.index.KacaIndex;
import org.glavo.kaca.index.KacaIndexBuilder;
import org.glavo.kaca.index.KacaIndexType;
import org.glavo.kaca.object.KacaObjectOptions;
import org.glavo.kaca.object.KacaObjectType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalKacaRepository extends KacaRepository {

    private final Path repositoryPath;
    private final FileChannel lockFileChannel;

    private byte[] ioBuffer;

    private LocalKacaRepository(boolean isReadOnly, KacaIndexType indexType,
                                Path repositoryPath, FileChannel lockFileChannel) {
        super(isReadOnly, indexType);
        this.repositoryPath = repositoryPath;
        this.lockFileChannel = lockFileChannel;
    }

    //region Private Member

    private byte[] getIOBuffer() {
        if (ioBuffer == null) {
            return ioBuffer = new byte[16 * 1024];
        }

        return ioBuffer;
    }

    private Path getTempDir() throws IOException {
        Path tmpDir = repositoryPath.resolve("tmp");
        Files.createDirectories(tmpDir);
        return tmpDir;
    }

    //endregion

    public Path getObjectFile(KacaIndex index) {
        getIndexType().checkIndex(index);

        if (index instanceof DigestKacaIndex) {
            DigestKacaIndex digestKacaIndex = (DigestKacaIndex) index;
            byte[] digest = digestKacaIndex.getDigestNoCopy();

        } else {
            throw new AssertionError("Unsupported index: " + index);
        }
    }

    public Path getRepositoryPath() {
        return repositoryPath;
    }

    public OutputStream putObject(KacaObjectType type, KacaObjectOptions options) throws IOException {
        checkWritable();

        Path tempFile = Files.createTempFile(getTempDir(), "object-", ".tmp");
        //noinspection resource
        OutputStream out = Files.newOutputStream(tempFile);

        return new OutputStream() {

            private boolean closed = false;
            private final byte[] singleBuffer = new byte[1];
            private final KacaIndexBuilder indexBuilder = getIndexType().newBuilder();

            @Override
            public void write(int b) throws IOException {
                singleBuffer[0] = (byte) (b);
                write(singleBuffer, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                indexBuilder.update(b, off, len);
                out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                super.flush();
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                out.close();

                // TODO: putObject
            }
        };
    }

    @Override
    public KacaIndex putObject(KacaObjectType type, KacaObjectOptions options, InputStream data) throws IOException {
        checkWritable();

        KacaIndexBuilder builder = getIndexType().newBuilder();
        byte[] buffer = getIOBuffer();

        Path tempFile = Files.createTempFile(getTempDir(), "object-", ".tmp");
        try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
            int n;
            while ((n = data.read(buffer)) > 0) {
                builder.update(buffer, 0, n);
                outputStream.write(buffer, 0, n);
            }
        } catch (Throwable e) {
            Files.delete(tempFile);
            throw e;
        }

        KacaIndex index = builder.build();

        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    public void close() throws IOException {
        lockFileChannel.close();
    }

}
