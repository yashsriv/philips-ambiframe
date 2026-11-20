# Project-specific R8 rules for the release build.
#
# None are needed today: every kotlinx.serialization serializer is referenced directly
# (Foo.serializer()), and OkHttp, WorkManager, Coil and kotlinx.serialization ship their own
# consumer rules. Components in the manifest are kept automatically.
