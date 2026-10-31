package com.alphaledger.reconciliation.run;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.AbstractResource;

import com.alphaledger.reconciliation.repository.ReconRepository;

/**
 * A stored custodian file as a re-openable stream. The driver would materialise a whole BYTEA value, so the content is
 * fetched in fixed slices and a restarted reader can simply open it again.
 */
class StoredFileResource extends AbstractResource {

    static final int SLICE_BYTES = 1024 * 1024;

    private final ReconRepository repository;
    private final long runId;

    StoredFileResource(ReconRepository repository, long runId) {
        this.repository = repository;
        this.runId = runId;
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public String getDescription() {
        return "custodian file of run " + runId;
    }

    @Override
    public InputStream getInputStream() {
        return new InputStream() {

            private byte[] slice = new byte[0];
            private int position;
            private long offset;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] target, int from, int length) {
                if (length == 0) {
                    return 0;
                }
                if (position == slice.length) {
                    slice = repository.fileSlice(runId, offset, SLICE_BYTES);
                    position = 0;
                    offset += slice.length;
                    if (slice.length == 0) {
                        return -1;
                    }
                }
                int count = Math.min(length, slice.length - position);
                System.arraycopy(slice, position, target, from, count);
                position += count;
                return count;
            }
        };
    }
}
