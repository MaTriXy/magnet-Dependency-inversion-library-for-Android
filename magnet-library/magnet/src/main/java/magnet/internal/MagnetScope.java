/*
 * Copyright (C) 2018 Sergej Shafarenka, www.halfbit.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package magnet.internal;

import magnet.Classifier;
import magnet.Scope;
import magnet.Scoping;
import magnet.SelectorFilter;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* Subject to change. For internal use only. */
final class MagnetScope implements Scope, FactoryFilter {

    private static final byte CARDINALITY_OPTIONAL = 0;
    private static final byte CARDINALITY_SINGLE = 1;
    private static final byte CARDINALITY_MANY = 2;

    private final MagnetScope parent;
    private final InstanceManager instanceManager;

    private boolean disposed = false;
    private List<WeakReference<MagnetScope>> children;

    /** Visible for testing */
    final int depth;

    /** Visible for testing */
    final Map<String, RuntimeInstances> instances;

    @SuppressWarnings("AnonymousHasLambdaAlternative")
    private final ThreadLocal<InstantiationContext> instantiationContext = new ThreadLocal<InstantiationContext>() {
        @Override protected InstantiationContext initialValue() { return new InstantiationContext(); }
    };

    MagnetScope(MagnetScope parent, InstanceManager instanceManager) {
        this.depth = parent == null ? 0 : parent.depth + 1;
        this.parent = parent;
        this.instanceManager = instanceManager;
        this.instances = new HashMap<>();
    }

    @Override
    public <T> T getOptional(Class<T> type) {
        checkNotDisposed();
        InstanceFactory<T> factory = instanceManager.getOptionalInstanceFactory(type, Classifier.NONE, this);
        return getSingleObject(type, Classifier.NONE, factory, CARDINALITY_OPTIONAL);
    }

    @Override
    public <T> T getOptional(Class<T> type, String classifier) {
        checkNotDisposed();
        InstanceFactory<T> factory = instanceManager.getOptionalInstanceFactory(type, classifier, this);
        return getSingleObject(type, classifier, factory, CARDINALITY_OPTIONAL);
    }

    @Override
    public <T> T getSingle(Class<T> type) {
        checkNotDisposed();
        InstanceFactory<T> factory = instanceManager.getOptionalInstanceFactory(type, Classifier.NONE, this);
        return getSingleObject(type, Classifier.NONE, factory, CARDINALITY_SINGLE);
    }

    @Override
    public <T> T getSingle(Class<T> type, String classifier) {
        checkNotDisposed();
        InstanceFactory<T> factory = instanceManager.getOptionalInstanceFactory(type, classifier, this);
        return getSingleObject(type, classifier, factory, CARDINALITY_SINGLE);
    }

    @Override
    public <T> List<T> getMany(Class<T> type) {
        checkNotDisposed();
        return getManyObjects(type, Classifier.NONE);
    }

    @Override
    public <T> List<T> getMany(Class<T> type, String classifier) {
        checkNotDisposed();
        return getManyObjects(type, classifier);
    }

    @Override
    public <T> Scope bind(Class<T> type, T object) {
        checkNotDisposed();
        bind(key(type, Classifier.NONE), object);
        return this;
    }

    @Override
    public <T> Scope bind(Class<T> type, T object, String classifier) {
        checkNotDisposed();
        bind(key(type, classifier), object);
        return this;
    }

    @Override
    public Scope createSubscope() {
        checkNotDisposed();
        MagnetScope child = new MagnetScope(this, instanceManager);
        if (children == null) children = new ArrayList<>(4);
        children.add(new WeakReference<>(child));
        return child;
    }

    @Override
    public void dispose() {
        checkNotDisposed();
        if (children != null) {
            for (WeakReference<MagnetScope> child : children) {
                MagnetScope scope = child.get();
                if (scope != null) {
                    scope.dispose();
                }
            }
        }
        // todo dispose instances
        disposed = true;
    }

    private void checkNotDisposed() {
        if (disposed) throw new IllegalStateException("Scope is disposed.");
    }

    @Override
    public boolean filter(InstanceFactory factory) {
        String[] selector = factory.getSelector();
        if (selector == null) {
            return true;
        }
        SelectorFilter selectorFilter = getSingle(SelectorFilter.class, selector[0]);
        if (selectorFilter == null) {
            throw new IllegalStateException(
                String.format("Factory %s requires selector '%s', which implementation is not available in the scope." +
                        " Make sure to add corresponding %s implementation to the classpath.",
                    factory, Arrays.toString(selector), SelectorFilter.class));
        }
        return selectorFilter.filter(selector);
    }

    private void bind(String key, Object object) {
        Object existing = instances.put(key, new RuntimeInstances<>(depth, null, object));
        if (existing != null) {
            throw new IllegalStateException(
                String.format("Instance of type %s already registered. Existing instance %s, new instance %s",
                    key, existing, object));
        }
    }

    private <T> List<T> getManyObjects(Class<T> type, String classifier) {
        List<InstanceFactory<T>> factories = instanceManager.getManyInstanceFactories(type, classifier, this);
        if (factories.size() == 0) return Collections.emptyList();

        List<T> objects = new ArrayList<>(factories.size());
        for (InstanceFactory<T> factory : factories) {
            T object = getSingleObject(type, classifier, factory, CARDINALITY_MANY);
            if (object != null) objects.add(object);
        }
        return objects;
    }

    @SuppressWarnings("unchecked")
    private <T> T getSingleObject(Class<T> type, String classifier, InstanceFactory<T> factory, byte cardinality) {
        InstantiationContext instantiationContext = this.instantiationContext.get();
        String key = key(type, classifier);

        if (factory == null) {
            RuntimeInstances<T> instance = findDeepInstance(key);
            if (instance == null) {
                if (cardinality == CARDINALITY_SINGLE) {
                    throw new IllegalStateException(
                        String.format(
                            "Instance of type '%s' (classifier: '%s') was not found in scopes.",
                            type.getName(), classifier));
                }
                return null;
            }
            instantiationContext.onDependencyFound(instance.getScopeDepth());
            return instance.getSingleInstance();
        }

        RuntimeInstances<T> deepInstances = findDeepInstance(key);
        boolean keepInScope = factory.getScoping() != Scoping.UNSCOPED;

        if (keepInScope) {
            if (deepInstances != null) {
                boolean isSingleOrOptional = cardinality != CARDINALITY_MANY;

                if (isSingleOrOptional) {
                    instantiationContext.onDependencyFound(deepInstances.getScopeDepth());
                    return deepInstances.getSingleInstance();
                }

                T object = deepInstances.getOptionalInstance((Class<InstanceFactory<T>>) factory.getClass());
                if (object != null) {
                    return object;
                }
            }
        }

        instantiationContext.onBeginInstantiation(key);

        T object = factory.create(this);

        int objectDepth = instantiationContext.onEndInstantiation();
        if (factory.getScoping() == Scoping.DIRECT) objectDepth = this.depth;
        instantiationContext.onDependencyFound(objectDepth);

        if (keepInScope) {

            boolean canUseDeepInstances = deepInstances != null
                && deepInstances.getScopeDepth() == objectDepth;

            if (canUseDeepInstances) {
                deepInstances.registerInstance(
                    (Class<InstanceFactory<T>>) factory.getClass(),
                    object
                );

            } else {
                registerInstanceInScope(
                    key,
                    objectDepth,
                    (Class<InstanceFactory<T>>) factory.getClass(),
                    object
                );
            }

            Class[] siblingFactoryTypes = factory.getSiblingTypes();
            if (siblingFactoryTypes != null) {
                for (int i = 0, size = siblingFactoryTypes.length; i < size; i += 2) {
                    String siblingKey = key(siblingFactoryTypes[i], classifier);
                    registerInstanceInScope(
                        siblingKey,
                        objectDepth,
                        (Class<InstanceFactory<T>>) siblingFactoryTypes[i + 1],
                        object
                    );
                }
            }
        }

        return object;
    }

    private <T> void registerInstanceInScope(
        String key, int depth, Class<InstanceFactory<T>> factoryType, T instance
    ) {
        if (this.depth == depth) {
            @SuppressWarnings("unchecked")
            RuntimeInstances<T> instances = this.instances.get(key);
            if (instances == null) {
                instances = new RuntimeInstances<>(depth, factoryType, instance);
                this.instances.put(key, instances);
            } else {
                instances.registerInstance(factoryType, instance);
            }
            return;
        }
        if (parent == null) {
            throw new IllegalStateException(
                String.format(
                    "Cannot register instance %s, type: %s, depth: %s",
                    instance, factoryType, depth
                )
            );
        }
        parent.registerInstanceInScope(key, depth, factoryType, instance);
    }

    @SuppressWarnings("unchecked")
    private <T> RuntimeInstances<T> findDeepInstance(String key) {
        RuntimeInstances<T> instances = (RuntimeInstances<T>) this.instances.get(key);
        if (instances == null && parent != null) {
            return parent.findDeepInstance(key);
        }
        return instances;
    }

    /** Visible for testing */
    static String key(Class<?> type, String classifier) {
        if (classifier == null || classifier.length() == 0) {
            return type.getName();
        }
        return classifier + "@" + type.getName();
    }

    private final static class InstantiationContext {
        private final ArrayDeque<Instantiation> instantiations = new ArrayDeque<>();
        private Instantiation currentInstantiation;

        void onBeginInstantiation(String key) {
            if (currentInstantiation != null) {
                instantiations.addFirst(currentInstantiation);
            }
            currentInstantiation = new Instantiation(key);
            if (instantiations.contains(currentInstantiation)) {
                throw createCircularDependencyException();
            }
        }

        int onEndInstantiation() {
            int resultDepth = currentInstantiation.depth;
            currentInstantiation = instantiations.isEmpty() ? null : instantiations.pollFirst();
            return resultDepth;
        }

        void onDependencyFound(int dependencyDepth) {
            if (currentInstantiation == null) return;
            if (dependencyDepth > currentInstantiation.depth) {
                currentInstantiation.depth = dependencyDepth;
            }
        }

        private IllegalStateException createCircularDependencyException() {

            Instantiation[] objects = instantiations.toArray(new Instantiation[0]);
            StringBuilder builder = new StringBuilder()
                .append("Magnet failed because of unresolved circular dependency: ");
            for (int i = objects.length; i-- > 0; ) {
                builder.append(objects[i].key).append(" -> ");
            }
            builder.append(currentInstantiation.key);

            return new IllegalStateException(builder.toString());
        }
    }

    private final static class Instantiation {
        final String key;
        int depth;

        Instantiation(String key) {
            this.key = key;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Instantiation that = (Instantiation) o;
            return key.equals(that.key);
        }

        @Override public int hashCode() {
            return key.hashCode();
        }
    }

}
