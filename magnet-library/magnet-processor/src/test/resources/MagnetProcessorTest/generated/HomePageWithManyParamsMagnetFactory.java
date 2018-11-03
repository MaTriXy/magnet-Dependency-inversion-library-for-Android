package app.extension;

import app.HomeRepository;
import app.Page;
import java.util.List;
import magnet.internal.InstanceFactory;
import magnet.internal.ScopeContainer;

public final class HomePageWithManyParamsMagnetFactory extends InstanceFactory<Page> {

    @Override
    public Page create(ScopeContainer scope) {
        List<HomeRepository> variant1 = scope.getMany(HomeRepository.class);
        List<HomeRepository> variant2 = scope.getMany(HomeRepository.class, "global");
        List<HomeRepository> variant3 = scope.getMany(HomeRepository.class);
        List<HomeRepository> variant4 = scope.getMany(HomeRepository.class, "global");
        return new HomePageWithManyParams(variant1, variant2, variant3, variant4);
    }

    public static Class getType() {
        return Page.class;
    }
}