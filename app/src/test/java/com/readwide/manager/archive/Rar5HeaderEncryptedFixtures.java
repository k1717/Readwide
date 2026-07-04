package com.readwide.manager.archive;

/**
 * Self-made RAR5 header-encrypted ({@code -hp}) fixtures for
 * {@link Rar5HeaderEncryptedArchiveTest}, base64 encoded. Built with the RAR
 * 7.00 CLI ({@code rar a -ma5 -hpspeak}, store and {@code -m3}) from a
 * deterministic first-party payload (40 numbered pangram lines, 2,680 bytes;
 * SHA-256 pinned in the test); no third-party archive content is embedded.
 * Both round trip through UNRAR 7.00 before embedding. Password:
 * {@code speak}.
 */
final class Rar5HeaderEncryptedFixtures {
    private Rar5HeaderEncryptedFixtures() {
    }

    static final String STORED_B64 =
            "UmFyIRoHAQBKUYQjIQQAAAEPuOGVggd5spvQWDGp+9OpHpjUFKl6y94D78D1krwsq/y9/OLKrPR81Q55532D3O2m/VB+C71+23Pe"
            + "OG23UHq5cqErBOhygDK/6R0PasORFlg5m1pfcyiXG8J1mluNSKweRzezmKgVF86UP2H4Ui7l10pAXPka6pgJXXqGnm/P4zjJLfUV"
            + "5kPd9CQmQ044BQIoA+T8ikfsQVFM8UMzTwq2jgZK9y8ujWbcK88Ch3IqaUZOyC3TtAq/bxqY0enLLlzNnSKQxKDSuFqQCfiaqlev"
            + "j2IrNE7kZK5QBLkMGozC7UKdKg/FVHWVJNVE4TkE1AcfN3ijC3dK3C8Vvc0LveKP17YXsx4Q4+Ve2W10mTMYlfDGupYhTrcSYocF"
            + "YqwPKTuyboxHbnc3WQn8ZGr5mO8KOGdwDK1qJXwCbPxci7m2eUXe3e4AilDlcWVzWrLLIkNfzrCdihpm+VmS5w7lSRkFOZElGFbF"
            + "GibcrTN6z+0zs/58WfohOvN4lbG6hB+7bmZ7uNRwA2IMrRJFCz3GKtv6pDdluJz8aIAqUAreLG565f8Z/Rc/zVpLbzzC5lU6Gv5O"
            + "PmLl3NRSd7Eywlz51wXv6/jBEd18Wv9U2iWZXJgwt08Mh6RcvhDc1quAlYEv241vMgwiFz4IqcBi95EDtQ2uqZdyMxDGu26XsAfi"
            + "vj51OOZHzyV/D1ATcD7AUdr6irJWvRn9m3oWk1c3LyqHkrgCYfXSzcuBansfHDBZ/6+3B/QD/e6EOS4bug8phohJftzvW7pq6Z8z"
            + "wAFnnXLHcHYYlHNx8EsUEo1ACzyUVj/D7sCvM8hAj4W3yWLOlicwt/PDnjAFGQfxPpvsolZ4MLuKWvtxK4fX2sLvAoW8AJepNUpA"
            + "Xzyv7zdZQrGcA50OaIKlP3JaGVyoYR9lglTVpg/FPjoCXB8n83nwmp7NPo7R22BkIQEQ2qpHqAx+UcOpQU2Cic9wQn8m97muzHHS"
            + "2cALdxiecu87ziQAvkVgDgyIF4KgHD1rEFSRvg9srG2jIk8fDde4SFHQbGd+U0G8BKFctVDQB6/yCzHKBPwVDJg3zEmxUDDkRnF2"
            + "va+v6BGk2I5B7z0g2zrtgPcnBb7+/YqajOdcL0qcXq2MJX9a9Gkei5S/8ODZR9tf3aqK//llYxrHV38GLU9Gn6FTB4JI/cdzjIla"
            + "9uyU9BG6x+I5v74Jxh+XnPAOODVLX5TzeqjrWvEaucBeXnZ1woJyQSNn9p2OKMgDdpwFpiQdOBjX2p0WKcK7zJJ8r5jPhw5+4MEy"
            + "Dsr2yc6Sjh3LH59WOk2j+KIPzgQ25fpfJBMjRMAiqPaePbATz0uAh28UjylZt9EzzoP9CtE6QyVwwRGKBggyfsYioZAgLIH+N5jz"
            + "VozS0OC2EBndf7zGmMlYjixdufNXDF4fHHAByCtaKyddgifbxL0qK6Nt8DHg3wPxzkhHWXxpg3113X5NIjFNCs7wDDyJNK82iiZD"
            + "ux+/+NfXPjkA16cUxCL8mroBmqz4VtYZYQLKPD8IQoxcNH9jrYv/TTfjx75bFUzGivfJ0s1e439NDDAcoCJvogdCWWolBWSEPtmL"
            + "sZxBvofQF8f0KI2hrskO2OS+oyHhzMCTSjvMXU0h04aFgxWxkLlZTRt8VeIacegNYJefsBbC/ga51+k4rgoSHYkEP8nzCvbo45t5"
            + "GZxT5pKJLtNKkZIOuwpIXZ+XyA0IswbRfUtsbyrA78eMtpK0q2xDO831z+6GEsaGe7XzawL2l6mks2jzWQ7FNMOXnH0prXF3f9ai"
            + "IjZ/zlp6d4xJlLlZ/sZgcFMIwf/XNx7EfrqUpeOSTyTauHQLhTrfhJtN5dc9nL2LWL0+UyB+Ex2GHLVGLTzDYjXoQ5zoxhJ1hG0s"
            + "C+j77OG/jMoGzO4wPM8f1OQCJoc/9vaeL3TM3g3V4e1m+dx/S56pdzwJXo72eQCqwbr8EVu6Qb5JdKzP+mkwzEZrBOsmjZrfCkhp"
            + "+TUtmKnnm5hypjsBILpjRUa1el5SjPxeQf3suYvicsFjVWVZA9Qm4cjlpJ1Q9QS96YkthcKVkzkj1+sFh9CPMKbaEgZ6PdW852w2"
            + "YrEtosJWrl44eHHGqNQgSdslMVPuZ7O5nwmoGRwKCzJXdzsHWFbSz9Uj4572fm39fj/sr0+8K3crqKdLlaeGVRbRdQQf+atEHEX2"
            + "cS4gcMJ4uIShW2T0IYmotYxLFPWdBJxt9ok+26jSaEmvLwgME9Vrzp+Z1GfZ7m1dN3vjcbZUJCxnXP+RhuJap6RdamRQTtGUo26X"
            + "1POXxoKjV7VVjsJjpZ86gjGq6RyuPJ6bhq8DRS3C8NPpO33xhsN2OF8h2tp20NSwDYG7Ux9uEC3L0Y/T8jkIVVlfJXqLATetWEQE"
            + "NRA4aEaWrLUfFz//LHp/AyHEJJGeWsAC0brXW88RZ5HY+/L7LzDx0POETNCk9yns9MkuYQ+lBYkssZ1odypaB4BiuAeSxgwtIX5T"
            + "WSjae6X3IC/KB+oLOAZW0lxN6DWlaPzkMO0Z3ZIJUFGWXHgmzHA+8u/CIft5xegJty4Nuxpiyw2UvCr8OD2o1EuZnqkKEp9XVLhE"
            + "MkqpuXXicdK/jNiBIuvtVZBsAolSUC14tomP70GnwzYgRHkfIlFrZTzgFZfBhK/IGcsV8roAK+rPqBSbJWRwZmpdcKAtay9pwORN"
            + "YsqfuFI7zszKJQI1AucrZ1SIxeY1V3Qf11l2Oe1b3Bp2XshwWn9F0RzkMlrz3oo+AewL7xrX6lw9OZU02ue/HFxbLiOWf+g1kyK0"
            + "S8Plf2OmkijM3zM3ByJ7Yugr68DJN+o009lE926ls7cXtMT/YQb1GCsMZMNxWitJbMey/pO5a9LAR4HtZF/QFn2Y5Pcasq9tdEvS"
            + "5i4eNDkLvxX/Aha4xyqS2SndNNmm1ok5Qf47rd0WSZF0U5CwaFW3pg0+0hqHldKSvLDydk/91K8njr0NChHGzkqoFO+Hz9QZ4IJi"
            + "LfCACg/BdsLRtYZr1IlhRvfEreAwttSXrYL+CcA8F6b5yBiUgT9U9RjnnNPJg72SnZnv5Xf5scIFTkoPMV/L8g8PPk05pBBRamQA"
            + "fB9ju+enn/VS85YTkU02vcSBZ8suf3PFW/IFpfIw039GgQQ2OMz5p/8/ZjP2rmu/JmVpIYF1U91z8dBdQVMWDBFytPg8ZD37gSn3"
            + "Nch9HtYA9zj4Cu8E0hWiY6CyOysGJWdsUvvoX6CYxHM1QWWeOSOs3miBeOr4/kPqZ3I7jtOWg3W/u5hQeWm36xso9+0I0X6sxveU"
            + "fn1+at/Zebjgr7KJK3iETY3eJLrlh2bzf8xnb2hUO1iLbriBoMEoSM5eUhfbQiM4z+7eONoa+njKe/vXaRowVLjcB+KAkhUFdbqQ"
            + "WmP247fZf2OEQ5zlI8YehAhkbtApmy2lj7w05pzyaxcorsrYA22N/c4kQfJSnwHrP8kWYa4lJ4P5MbbXoF4LZXDbiUo5pQx+eQmT"
            + "0zV5Dg0WXAiQO5QlAxKdOzp05EXBuRubyJsInn5w2QhbZyFQEunyqUjTuz/d3qAZIn+220wMv6EgK2n0LBodHGNkN4Mlf4egcllF"
            + "5E1CS610HLXHGKRvg3yrCf2LdbNhU475dyF8xpy/ffnEogOudJJaTE311oguR0oRqqAzojjKFP0Km7x3nFnFkcEso5yfAJZgASnu"
            + "+g/V6OqIANnRGD6m6jS1I5YZ2+zMtIFjgnViQc281cb9u5QyWSQ4Ycb0kjTJNQOGFYGrxPzM7uTGbM/gzFcyMbQ8Dudsvja+Ybup"
            + "NZigizV8lvqpDL37n2AkkRLJAhbuZqRQRITutlHg5Nzf11xKyICZjCI2SMctx6jlSpwV+GQ97T680Nxm";

    static final String COMPRESSED_B64 =
            "UmFyIRoHAQD91wYCIQQAAAEP1bDYJ///FyPFKK//reJRoZhVjcF8KXfLL+1oJv/8IJl0Oy2+TJAAuTrC0/fFf93CAbGEKpH/3aee"
            + "/176xip+N/W90Fv/q3IU1/YnhqAcVepls7IISm+Z2yHFOmQMEMgrb7O3jslsuApPDg4ZZGTrSl3ZSN7J6nZTmlWKyGiSWpq6YoMg"
            + "owfHb4a+WPJHyt0s6K6LETTORlhB1SkjRI8t/zrPRURrF0cmS1PX1aPTA+sI2FV9PXrge9t2N8ocZIj21Ky8c01aKKLQXpmERvfn"
            + "FAw3CHqdssrRuz0cjSrymuLD6fyRVRKlVczf+qZR35iY3rCYdClXZiZINpc7zkdqPWFyre0mPwW5QM4tRyAAukrY0gZoiyT8rLqh"
            + "27J5GbqTbdPqiqBFrIICONo2GdvByAd+w+uW7o23uZNXEBG0lHwVpe1TVMPOZ65C6aoVHYGjw/sYr7s7ZdkpG59v";
}
