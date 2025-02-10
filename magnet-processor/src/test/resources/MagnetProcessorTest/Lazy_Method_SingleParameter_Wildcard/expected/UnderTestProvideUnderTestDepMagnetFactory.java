package app;

import kotlin.Lazy;
import magnet.Scope;
import magnet.internal.Generated;
import magnet.internal.InstanceFactory;
import magnet.internal.SingleLazy;

@Generated
public final class UnderTestProvideUnderTestDepMagnetFactory extends InstanceFactory<UnderTest> {
    @Override
    public UnderTest create(Scope scope) {
        Lazy<String> dep = new SingleLazy(scope, String.class, "");
        return UnderTest.provideUnderTest(dep);
    }

    public static Class getType() {
        return UnderTest.class;
    }
}