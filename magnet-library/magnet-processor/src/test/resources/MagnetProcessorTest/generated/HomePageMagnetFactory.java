package app.extension;

import app.HomeRepository;
import app.Page;
import app.UserData;
import magnet.internal.InstanceFactory;
import magnet.internal.ScopeContainer;

public final class HomePageMagnetFactory extends InstanceFactory<Page> {

    @Override
    public Page create(ScopeContainer scope) {
        HomeRepository homeRepository = scope.getSingle(HomeRepository.class);
        UserData userData = scope.getSingle(UserData.class);
        return new HomePage(homeRepository, userData, scope);
    }

    public static Class getType() {
        return Page.class;
    }
}