/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.model.collection.internal

import org.gradle.api.Named
import org.gradle.api.PolymorphicDomainObjectContainer
import org.gradle.api.internal.ClosureBackedAction
import org.gradle.api.internal.DefaultPolymorphicDomainObjectContainer
import org.gradle.internal.reflect.DirectInstantiator
import org.gradle.model.*
import org.gradle.model.collection.CollectionBuilder
import org.gradle.model.internal.core.*
import org.gradle.model.internal.core.rule.describe.SimpleModelRuleDescriptor
import org.gradle.model.internal.fixture.ModelRegistryHelper
import org.gradle.model.internal.registry.UnboundModelRulesException
import org.gradle.model.internal.type.ModelType
import spock.lang.Specification

import static org.gradle.util.TextUtil.normaliseLineSeparators

class DefaultCollectionBuilderTest extends Specification {

    def type = new ModelType<NamedThing>() {}

    class NamedThing implements Named {
        String name
        String other
    }

    class SpecialNamedThing extends NamedThing implements Special {
    }

    def containerPath = ModelPath.path("container")
    def containerType = new ModelType<PolymorphicDomainObjectContainer<NamedThing>>() {}
    def collectionBuilderType = new ModelType<CollectionBuilder<NamedThing>>() {}
    def registry = new ModelRegistryHelper()
    def container = new DefaultPolymorphicDomainObjectContainer<NamedThing>(NamedThing, new DirectInstantiator(), { it.getName() })

    def setup() {
        BridgedCollections.dynamicTypes(registry, containerPath, "container", containerType, containerType, ModelType.of(NamedThing), container, Named.Namer.forType(NamedThing), BridgedCollections.itemDescriptor("container"))
        container.registerFactory(NamedThing) {
            NamedThing.newInstance(name: it)
        }
        container.registerFactory(SpecialNamedThing) { SpecialNamedThing.newInstance(name: it) }
    }

    void mutate(@DelegatesTo(CollectionBuilder) Closure<? super CollectionBuilder<NamedThing>> action) {
        def mutator = Stub(ModelAction)
        mutator.subject >> ModelReference.of(containerPath, new ModelType<CollectionBuilder<NamedThing>>() {})
        mutator.descriptor >> new SimpleModelRuleDescriptor("foo")
        mutator.execute(*_) >> { new ClosureBackedAction<NamedThing>(action).execute(it[1]) }

        registry.configure(ModelActionRole.Mutate, mutator)
    }

    void realize() {
        registry.realizeNode(containerPath)
    }

    def "can define an item with name"() {
        when:
        mutate { create("foo") }
        realize()

        then:
        container.getByName("foo") != null
        registry.realize(containerPath.child("foo"), ModelType.of(NamedThing)) == container.getByName("foo")
    }

    def "does not eagerly create item"() {
        when:
        mutate {
            create("foo")
            create("bar")
        }

        then:
        container.isEmpty()

        when:
        realize()

        then:
        container.getByName("bar")
    }

    def "can define item with custom type"() {
        when:
        mutate { create("foo", SpecialNamedThing) }
        realize()

        then:
        container.getByName("foo") instanceof SpecialNamedThing
    }

    def "can define item using filtered collection"() {
        when:
        mutate {
            withType(SpecialNamedThing).create("foo")
            withType(NamedThing).create("bar")
        }
        realize()

        then:
        container.getByName("foo") instanceof SpecialNamedThing
        container.getByName("bar").class == NamedThing
    }

    def "fails when using filtered collection to define item of type that is not assignable to collection item type"() {
        when:
        mutate {
            withType(String).create("foo")
        }
        realize()

        then:
        ModelRuleExecutionException e = thrown()
        e.cause instanceof IllegalArgumentException
        e.cause.message == "Cannot create an item of type java.lang.String as this is not a subtype of $NamedThing.name."
    }

    def "can register config rules for item"() {
        when:
        mutate {
            create("foo") {
                other = "changed"
            }
        }
        realize()

        then:
        container.getByName("foo").other == "changed"
    }

    def "can register config rule and type for item"() {
        when:
        mutate {
            create("foo", SpecialNamedThing) {
                other = "changed"
            }
        }
        realize()

        then:
        container.getByName("foo").other == "changed"
    }

    def "can query collection size"() {
        when:
        mutate {
            assert size() == 0
            assert it.isEmpty()

            create("a")
            create("b")

            assert size() == 2
            assert !isEmpty()
        }

        then:
        registry.realize(containerPath, collectionBuilderType).size() == 2
    }

    def "can query filtered collection size"() {
        when:
        mutate {
            create("a")
            create("b", SpecialNamedThing)

            assert withType(SpecialNamedThing).size() == 1
            assert withType(Special).size() == 1
            assert withType(NamedThing).size() == 2
            assert withType(String).size() == 0

            assert !withType(SpecialNamedThing).isEmpty()
            assert withType(String).isEmpty()
        }

        then:
        registry.realize(containerPath, collectionBuilderType).withType(SpecialNamedThing).size() == 1
    }

    def "can query collection membership"() {
        when:
        mutate {
            assert !containsKey("a")
            assert !containsKey(12)

            create("a")
            create("b")

            assert it.containsKey("a")
        }

        then:
        registry.realize(containerPath, collectionBuilderType).containsKey("a")
    }

    def "can query filtered collection membership"() {
        when:
        mutate {
            assert !withType(NamedThing).containsKey("a")
            assert !withType(Integer).containsKey(12)

            create("a")
            create("b", SpecialNamedThing)

            assert withType(Object).containsKey("a")
            assert withType(NamedThing).containsKey("a")
            assert !withType(SpecialNamedThing).containsKey("a")
            assert !withType(Special).containsKey("a")
            assert !withType(String).containsKey("a")

            assert withType(Object).containsKey("b")
            assert withType(NamedThing).containsKey("b")
            assert withType(SpecialNamedThing).containsKey("b")
            assert withType(Special).containsKey("b")
            assert !withType(String).containsKey("b")
        }

        then:
        registry.realize(containerPath, collectionBuilderType).withType(SpecialNamedThing).containsKey("b")
    }

    def "can query collection keys"() {
        when:
        mutate {
            assert keySet().isEmpty()

            create("a")
            create("b")

            assert keySet() as List == ["a", "b"]
        }

        then:
        registry.realize(containerPath, collectionBuilderType).keySet() as List == ["a", "b"]
    }

    def "can query filtered collection keys"() {
        when:
        mutate {
            assert withType(NamedThing).keySet().isEmpty()
            assert withType(String).keySet().isEmpty()

            create("b", SpecialNamedThing)
            create("a")

            assert withType(NamedThing).keySet() as List == ["a", "b"]
            assert withType(SpecialNamedThing).keySet() as List == ["b"]
            assert withType(Special).keySet() as List == ["b"]
            assert withType(String).keySet().isEmpty()
        }

        then:
        registry.realize(containerPath, collectionBuilderType).withType(Special).keySet() as List == ["b"]
    }

    def "can register mutate rule for item with name"() {
        when:
        mutate {
            named("foo") {
                assert other == "original"
                other = "changed"
            }
            create("foo") {
                other = "original"
            }
        }
        realize()

        then:
        container.getByName("foo").other == "changed"
    }

    def "can register mutate rule for item with name using filtered container"() {
        when:
        mutate {
            withType(Object).named("foo") {
                other += " Object"
            }
            withType(Special).named("foo") {
                other += " Special"
            }
            withType(SpecialNamedThing).named("foo") {
                other += " SpecialNamedThing"
            }
            create("foo", SpecialNamedThing) {
                other = "types:"
            }
        }
        realize()

        then:
        container.getByName("foo").other == "types: Object Special SpecialNamedThing"
    }

    def "fails when named item does not have view with appropriate type"() {
        when:
        mutate {
            withType(String).named("foo") {
            }
            create("foo")
        }
        realize()

        then:
        InvalidModelRuleException e = thrown()
        e.cause instanceof ModelRuleBindingException
        e.cause.message.startsWith("Model reference to element 'container.foo' with type java.lang.String is invalid due to incompatible types.")
    }

    static class SetOtherToName extends RuleSource {
        @Mutate
        void set(NamedThing thing) {
            thing.other = thing.name
        }
    }

    /**
     * This test documents the current behaviour, not necessarily the desired.
     *
     * Ideally, we'd get a failure here indicating that container item 'foo' is not String & NamedThing
     */
    def "rules targeting item of mismatched type are allowed"() {
        when:
        mutate {
            withType(String).named("foo", SetOtherToName)
            create("foo")
        }
        realize()

        then:
        registry.get(containerPath.child("foo")).other == "foo"
    }

    def "can register mutate rule for all items using filtered container"() {
        when:
        mutate {
            withType(Named).all {
                other += " Named"
            }
            withType(String).all {
                other += " String"
            }
            withType(NamedThing).all {
                other += " NamedThing"
            }
            withType(Special).all {
                other += " Special"
            }
            withType(SpecialNamedThing).all {
                other += " SpecialNamedThing"
            }
            create("foo") {
                other = "types:"
            }
            create("bar", SpecialNamedThing) {
                other = "types:"
            }
        }
        realize()

        then:
        container.getByName("foo").other == "types: Named NamedThing"
        container.getByName("bar").other == "types: Named NamedThing Special SpecialNamedThing"
    }

    def "can register mutate rule for all items"() {
        when:
        mutate {
            all {
                assert other == "original"
                other = "changed"
            }
            create("foo") {
                other = "original"
            }
        }
        realize()

        then:
        container.getByName("foo").other == "changed"
    }

    def "can register mutate rule for all items with specific type"() {
        when:
        mutate {
            withType(Named) {
                other += " Named"
            }
            withType(String) {
                other += " String"
            }
            withType(Special) {
                other += " Special"
            }
            withType(SpecialNamedThing) {
                other += " SpecialNamedThing"
            }
            create("foo") {
                other = "foo:"
            }
            create("bar", SpecialNamedThing) {
                other = "bar:"
            }
        }
        realize()

        then:
        container.getByName("foo").other == "foo: Named"
        container.getByName("bar").other == "bar: Named Special SpecialNamedThing"
    }

    def "can register defaults rule for all items"() {
        when:
        mutate {
            all {
                other += " all{}"
            }
            create("foo") {
                other += " create()"
            }
            beforeEach {
                other = "beforeEach{}"
            }
        }
        realize()

        then:
        container.getByName("foo").other == "beforeEach{} create() all{}"
    }

    def "can register defaults rule for all items with type"() {
        when:
        mutate {
            beforeEach(Named) {
                other = "Named"
            }
            beforeEach(String) {
                other += " String"
            }
            beforeEach(Special) {
                other += " Special"
            }
            beforeEach(SpecialNamedThing) {
                other += " SpecialNamedThing"
            }
            create("foo") {
                other += " create(foo)"
            }
            create("bar", SpecialNamedThing) {
                other += " create(bar)"
            }
        }
        realize()

        then:
        container.getByName("foo").other == "Named create(foo)"
        container.getByName("bar").other == "Named Special SpecialNamedThing create(bar)"
    }

    def "can register finalize rule for all items"() {
        when:
        mutate {
            all {
                other += " all{}"
            }
            afterEach {
                other += " afterEach{}"
            }
            create("foo") {
                other = "create()"
            }
        }
        realize()

        then:
        container.getByName("foo").other == "create() all{} afterEach{}"
    }

    def "provides groovy DSL"() {
        when:
        mutate {
            foo {
                assert other == "original"
                other = "changed"
            }
            foo(NamedThing) {
                other = "original"
            }
            bar(SpecialNamedThing)
        }
        realize()

        then:
        container.getByName("foo").other == "changed"
        container.getByName("bar") instanceof SpecialNamedThing
    }

    class MutableValue {
        String value
    }

    class Bean {
        String name
        String value
    }

    def "sensible error is thrown when trying to apply a class that does not extend RuleSource as a scoped rule"() {
        def cbType = DefaultCollectionBuilder.typeOf(ModelType.of(MutableValue))
        def iType = DefaultCollectionBuilder.instantiatorTypeOf(ModelType.of(MutableValue))
        def iRef = ModelReference.of("instantiator", iType)

        registry
                .create(ModelCreators.bridgedInstance(iRef, { name, type -> new MutableValue() }).build())
                .collection("values", MutableValue, iRef)
                .mutate {
            it.descriptor("mutating elements").path "values" type cbType action { c ->
                c.create("element")
                c.named("element", Object)
            }
        }

        when:
        registry.realize(ModelPath.path("values"), ModelType.UNTYPED)

        then:
        ModelRuleExecutionException e = thrown()
        e.cause.class == InvalidModelRuleDeclarationException
        e.cause.message == "Type java.lang.Object is not a valid model rule source: rule source classes must directly extend org.gradle.model.RuleSource"
    }

    static class ElementRules extends RuleSource {
        @Mutate
        void connectElementToInput(Bean element, String input) {
            element.value = input
        }
    }

    def "inputs of a rule from an inner source are not realised if the rule is not required"() {
        given:
        def cbType = DefaultCollectionBuilder.typeOf(ModelType.of(Bean))
        def iType = DefaultCollectionBuilder.instantiatorTypeOf(Bean)
        def iRef = ModelReference.of("instantiator", iType)
        def events = []
        registry
                .create(ModelCreators.bridgedInstance(iRef, { name, type -> new Bean(name: name) } as NamedEntityInstantiator).build())
                .create("input", "input") { events << "input created" }
                .collection("beans", Bean, iRef)
                .mutate {
            it.path "beans" type cbType action { c ->
                events << "collection mutated"
                c.create("element") { events << "$it.name created" }
                c.named("element", ElementRules)
            }

        }

        when:
        registry.atState(ModelPath.path("beans"), ModelNode.State.SelfClosed)

        then:
        events == ["collection mutated"]

        when:
        registry.atState(ModelPath.path("beans"), ModelNode.State.GraphClosed)

        then:
        events == ["collection mutated", "element created", "input created"]
    }

    def "model rule with by-path dependency on non task related collection element's child that does exist passes validation"() {
        def cbType = DefaultCollectionBuilder.typeOf(ModelType.of(Bean))
        def iType = DefaultCollectionBuilder.instantiatorTypeOf(Bean)
        def iRef = ModelReference.of("instantiator", iType)

        registry
                .create(ModelCreators.bridgedInstance(iRef, { name, type -> new Bean(name: name) } as NamedEntityInstantiator).build())
                .createInstance("foo", new Bean())
                .mutate {
            it.path("foo").type(Bean).action("beans.element.mutable", ModelType.of(MutableValue)) { Bean subject, MutableValue input ->
                subject.value = input.value
            }
        }
        .collection("beans", Bean, iRef)
                .mutate {
            it.path "beans" type cbType action { c ->
                c.create("element")
            }
        }
        .mutate {
            it.path "beans.element" node {
                it.addLink(registry.instanceCreator("beans.element.mutable", new MutableValue(value: "bar")))
            }
        }

        when:
        registry.bindAllReferences()

        then:
        noExceptionThrown()
    }

    static class ByTypeSubjectBoundToScopeChildRule extends RuleSource {
        @Mutate
        void mutateScopeChild(MutableValue value) {
            value.value = "foo"
        }
    }

    def "model rule with by-type dependency on non task related collection element's child that does exist passes validation"() {
        given:
        def cbType = DefaultCollectionBuilder.typeOf(ModelType.of(Bean))
        def iType = DefaultCollectionBuilder.instantiatorTypeOf(Bean)
        def iRef = ModelReference.of("instantiator", iType)

        registry
                .create(ModelCreators.bridgedInstance(iRef, { name, type -> new Bean(name: name) } as NamedEntityInstantiator).build())
                .collection("beans", Bean, iRef)
                .mutate {
            it.path "beans" type cbType action { c ->
                c.create("element")
                c.named("element", ByTypeSubjectBoundToScopeChildRule)
            }
        }
        .mutate {
            it.path "beans.element" node {
                it.addLink(registry.instanceCreator("beans.element.mutable", new MutableValue()))
            }
        }

        when:
        registry.bindAllReferences()

        then:
        noExceptionThrown()
    }

    def "adding an unbound scoped rule for an element that is never created results in an error upon validation if the scope parent has been self closed"() {
        given:
        def cbType = DefaultCollectionBuilder.typeOf(ModelType.of(Bean))
        def iType = DefaultCollectionBuilder.instantiatorTypeOf(Bean)
        def iRef = ModelReference.of("instantiator", iType)

        registry
                .create(ModelCreators.bridgedInstance(iRef, { name, type -> new Bean(name: name) }).build())
                .collection("beans", Bean, iRef)
                .mutate {
            it.path "beans" type cbType action { c ->
                c.named("element", ElementRules)
            }
        }

        when:
        registry.atState(ModelPath.path("beans"), ModelNode.State.SelfClosed)
        registry.bindAllReferences()

        then:
        UnboundModelRulesException e = thrown()
        normaliseLineSeparators(e.message) == """The following model rules are unbound:
  $ElementRules.name#connectElementToInput($Bean.name, $String.name)
    Mutable:
      - <unspecified> ($Bean.name) parameter 1 in scope of 'beans.element\'
    Immutable:
      - <unspecified> ($String.name) parameter 2"""
    }
}
