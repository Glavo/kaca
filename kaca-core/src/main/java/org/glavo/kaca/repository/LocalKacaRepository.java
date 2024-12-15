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

import org.glavo.kaca.function.CheckedConsumer;
import org.glavo.kaca.index.DigestKacaIndex;
import org.glavo.kaca.index.KacaIndex;
import org.glavo.kaca.index.KacaIndexBuilder;
import org.glavo.kaca.index.KacaIndexType;
import org.glavo.kaca.internal.util.Hex;
import org.glavo.kaca.object.KacaObjectOptions;
import org.glavo.kaca.object.KacaObjectType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public final class LocalKacaRepository extends KacaRepository {

    private final Path repositoryPath;
    private final FileChannel lockFileChannel;

    private final Path objectsPath;

    private byte[] ioBuffer;

    private LocalKacaRepository(boolean isReadOnly, KacaIndexType indexType,
                                Path repositoryPath, FileChannel lockFileChannel) {
        super(isReadOnly, indexType);
        this.repositoryPath = repositoryPath;
        this.lockFileChannel = lockFileChannel;

        this.objectsPath = repositoryPath.resolve("objects");
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

    private void putTempObjectFile(KacaIndex index, Path tempFile) throws IOException {
        Path objectFile = getObjectFile(index);

        if (Files.exists(objectFile)) {
            throw new UnsupportedOperationException(); // TODO
        }

        Files.createDirectories(objectFile.getParent());
        Files.move(tempFile, objectFile);
    }

    //endregion

    public Path getRepositoryPath() {
        return repositoryPath;
    }

    public Path getObjectFile(KacaIndex index) {
        getIndexType().checkIndex(index);

        if (index instanceof DigestKacaIndex) {
            DigestKacaIndex digestKacaIndex = (DigestKacaIndex) index;

            return objectsPath.resolve(Hex.encodeHex(digestKacaIndex.getDigestNoCopy()[0]))
                    .resolve(digestKacaIndex.getDigestToString());
        } else {
            throw new AssertionError("Unsupported index: " + index);
        }
    }

    @Override
    public KacaIndex putObject(KacaObjectType type, KacaObjectOptions options, CheckedConsumer<OutputStream, ?> consumer) throws IOException {
        checkWritable();

        Path tempFile = Files.createTempFile(getTempDir(), "object-", ".tmp");

        KacaIndexBuilder indexBuilder = getIndexType().newBuilder();

        //noinspection resource
        OutputStream out = Files.newOutputStream(tempFile);
        ArrayList<Throwable> errors = new ArrayList<>();

        try (OutputStream wrappedOutputStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                try {
                    indexBuilder.update((byte) b);
                    out.write(b);
                } catch (Throwable e) {
                    errors.add(e);
                    throw e;
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    indexBuilder.update(b, off, len);
                    out.write(b, off, len);
                } catch (Throwable e) {
                    errors.add(e);
                    throw e;
                }
            }

            @Override
            public void flush() throws IOException {
                try {
                    super.flush();
                } catch (Throwable e) {
                    errors.add(e);
                    throw e;
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    out.close();
                } catch (Throwable e) {
                    errors.add(e);
                    throw e;
                }
            }
        }) {
            consumer.accept(wrappedOutputStream);
        } catch (Throwable e) {
            IOException ioe = new IOException("Some exception occurred during the call to the consumer", e);

            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e2) {
                ioe.addSuppressed(e2);
            }
            throw ioe;
        }

        if (!errors.isEmpty()) {
            IOException e = new IOException("Some exception occurred during the call to the consumer");
            for (Throwable error : errors) {
                e.addSuppressed(error);
            }
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }

        KacaIndex index = indexBuilder.build();
        putTempObjectFile(index, tempFile);
        return index;
    }

    @Override
    public KacaIndex putObject(KacaObjectType type, KacaObjectOptions options, InputStream data) throws IOException {
        checkWritable();

        KacaIndexBuilder indexBuilder = getIndexType().newBuilder();
        byte[] buffer = getIOBuffer();

        Path tempFile = Files.createTempFile(getTempDir(), "object-", ".tmp");
        try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
            int n;
            while ((n = data.read(buffer)) > 0) {
                indexBuilder.update(buffer, 0, n);
                outputStream.write(buffer, 0, n);
            }
        } catch (Throwable e) {
            Files.delete(tempFile);
            throw e;
        }

        KacaIndex index = indexBuilder.build();
        putTempObjectFile(index, tempFile);
        return index;
    }

    @Override
    public void close() throws IOException {
        lockFileChannel.close();
    }

}
