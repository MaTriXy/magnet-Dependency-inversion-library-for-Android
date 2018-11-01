package app.extension.utils;

import app.HomeRepository;
import app.Page;
import java.util.List;
import magnet.Scoping;
import magnet.internal.InstanceFactory;
import magnet.internal.InstanceScope;

public final class HomePageWithStaticConstructorSingleCreateRepositoriesMagnetFactory extends InstanceFactory<Page> {

    @Override
    public Page create(InstanceScope scope) {
        List<HomeRepository> repositories = scope.getMany(HomeRepository.class);
        return HomePageWithStaticConstructorSingle.create(repositories);
    }

    @Override
    public Scoping getScoping() {
        return Scoping.UNSCOPED;
    }

    public static Class getType() {
        return Page.class;
    }

}