package com.readwide.manager.archive;

/**
 * Self-made 7z PPMd fixtures for {@link SevenZPpmdArchiveTest}, base64
 * encoded. Built by p7zip ({@code -m0=PPMd:mem=64k:o=6}) from a deterministic
 * first-party payload (400 numbered pangram lines, 31,600 bytes; see the test
 * javadoc for the pinned SHA-256); no third-party archive content is
 * embedded. Both fixtures were extracted with the reference {@code 7z} tool
 * to confirm they round trip before embedding. Password for the AES fixture
 * ({@code -mhe=on}): {@code pw1717}.
 */
final class Sevenz7PpmdFixtures {
    private Sevenz7PpmdFixtures() {
    }

    static final String PLAIN_B64 =
            "N3q8ryccAASgu2zaZQIAAAAAAABiAAAAAAAAALu5j28AUAHi+/UYpaSj52Nq1PPQMxEpPEAROyTa3/fiAlaAX6gAznZXliqIvLBQ"
            + "A6i2Nrp7aZ8+BnYo2KWnJJH7cfzJxvQWEdN1RaWkAGNGy/ccpReHN+YikkP/NnS6vIgVu7rZ4GnYQ6K9xZZXnIHYH6YYfnQaTJP+"
            + "U2iUTKMH79jEZJHTYxdZz6TmlM35KS69jwcLE2Ml/2wOU/NAc2HDJsLxop20cdK7sbYEZ+Kp2teEaRglLLZH76IUub2nPx2A+5BL"
            + "3wlP7AMInwIonJ+JIcBTo8ETighkXwn7tPMEELOk3oW9DLhV0ADOgR2LGS9OVgkUeiqhxlMMUApUQO5KuA+TBp360fMquqfT2yfU"
            + "oRzk9BMdJ2RFWQQg94PoWLbX6L8bg8NCCIXWuDUgHPMG+fghrHATC8UtnMKB9aw388QnPM1cN/cIzIbvSLUw6LKaspxs+05jP9z4"
            + "Qmo3f3HCiWMUtMvc+fyRe9rwxqMIjrm7MhKWKPUFj0pkUKjITjtCHmMtNEYvul7xg++MbUEw3PPkerV9r1oKG0xUW+LpW22PakV9"
            + "5SPGOkG7e782HuPlXXau8WtiEM9aTYkda5QeuL4iIsqZsPVO6JUPNds+RLzL1MaM4IMo0tReJLJI9UQKoGZQrx2HJHLxtaUiEcse"
            + "cyUQcIRqxOt+UrGtQ73Vk5uoe1A0UyawJ3aiTVbeaSg/N7WoKmvsxSm4NVqoJyu0H87NvMgMwK3JGofBmT+e3AVxFdTXVt9Y6PZa"
            + "wGTZX9JGRvQKJ6vd+Hq5WdkaeTrzCk+77UWdbo4VqMpjzUxJPXZV/bycvtWZAQQGAAEJgmUABwsBAAEjAwQBBQYAAAEADMBwewAI"
            + "CgEMij41AAAFARkDAAAAERkAcABwAG0AZAAtAHMAbQAuAHQAeAB0AAAAGQIAABQKAQBj9oz7/wrdARUGAQAggKSBAAA=";

    static final String AES_B64 =
            "N3q8ryccAARkb8VBAAMAAAAAAAAxAAAAAAAAAP+uA2y5Pd3jSUuSb66z7SQwQdkwefSNEEkcUyjjjETYE9G4NgA5ubenFE/GRPb0"
            + "Z0W5o9ocPSYh0j+OtS2FsSPTkcEbHPBLSvYA0W10fXmglIAuNzowh0NbLAffwVYO+mnWvSbbsmqXlG9GYjAB/ii+8steULoubsh9"
            + "KZfsdKQG32UnkQiDkCptn6WaRjf+xB/D8PJeRD7+KVwzsXawmtDTXtswoVIcTT7F4uE+RVvT+LKe0Orf77pwtoR8hnyddcQQWoPI"
            + "Nl80FPZKoCdlmEymdoEXKljZxaLITHWqTi/1+kGP3Ont7idQxEk6aeqy03xIGZun4lyGkw2VshTz0drFKPpf0PG89+GEfxuA+dO+"
            + "Ff1yUhTCyCDgVU+1YFigmVBsg/6HwOkMGEUM7eEDuGK1m4uEegkYyiowRpznJ4rFAUDeqF+mxrdOsXYeleh54l/SMwblf7lGUvID"
            + "XfH44AAREeYhikpTBZcDKeA+h9A2GpBQq77zgRfRwLhvAL8FZTpt9G+VhMc+dAioyT83R2TfVN/dZqYVDnYljD9AaKca9sJ133Zj"
            + "OF4MyGWY7hSGnCgAca07k4oTP9FBsYJQLqp27S04DQNJ6gqfMzAG9RiKrn3Z65zZrZTUFdqkiKmpTpa9iHWnoiZOpBosjl2De1aL"
            + "IQo/GEPaamQMKaEwLD9JLva/xLUE4Z2fS/6Hfx120nHNLGZueEOOCjjZvnrzT5wVE8F2x4JBIEtmzhu+6RLjpbFiif9110opOr4P"
            + "nfcQLlGZxvPDAJ4iooTw1Yi60pQZPP5TnGmydZg9i0q4r/jMtVpP7dDagt0jG0fxF++QU95ThinS800ExtarypO4qyhYZKxN76Xo"
            + "a1/dKASGSVCqdENQqaMysQ7ZnG+JBZ2itsBLPogvhzy8FMPnOKLntAXDgUygnKX0WMNqOHVBPqGLhJpJzsR3zc5nu92p6gbn4iCj"
            + "kkF6sgzeahrh/+DgFKqP4Qcaf6Rpl6F+IUrf7CnPdj/iC1rehp+Rmdw/Ivj388mDs+UXBoJwAQmAkAAHCwEAASQG8QcBElMPt2hZ"
            + "FPF6xNDNPGwIjol4tQyAggoB/nFMkAAA";
}
