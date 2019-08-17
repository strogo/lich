/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.lich.component

import android.content.Context
import java.util.ServiceLoader

/**
 * A class to declare a factory of a "component".
 *
 * Inherit this class for the companion object of a component class.
 * ```
 * class FooComponent {
 *
 *     // snip...
 *
 *     companion object : ComponentFactory<FooComponent>() {
 *         override fun createComponent(context: Context): FooComponent =
 *             FooComponent()
 *     }
 * }
 * ```
 *
 * The singleton instance of each component can be obtained via [Context.getComponent].
 * Therefore, the singleton instance of `FooComponent` can be obtained by this code:
 *
 * ```
 * val fooComponent = context.getComponent(FooComponent)
 * ```
 *
 * You can also obtain components *lazily* using [Context.component] like this:
 *
 * ```
 * val fooComponent by context.component(FooComponent)
 * ```
 *
 * @param T the type of the component that will be created by this factory.
 * @see Context.getComponent
 * @see Context.component
 */
abstract class ComponentFactory<T : Any> {
    /**
     * Creates a component for this factory.
     *
     * @param context the application context.
     */
    protected abstract fun createComponent(context: Context): T

    /**
     * Delegates the creation of a component to a [DelegatedComponentFactory] specified by name.
     *
     * This function instantiates the class specified by [delegatedFactoryClassName], and calls
     * its [DelegatedComponentFactory.createComponent] function, then returns the result.
     *
     * You can use this function to divide dependencies in a multi-module project.
     * Assumes that there are two modules "base" and "foo" such that "foo" depends on "base".
     * If you want to call some function of "foo" from "base", implement like this:
     * ```
     * // "base" module
     * package module.base.facades
     *
     * interface FooModuleFacade {
     *
     *     fun launchFooActivity()
     *
     *     companion object : ComponentFactory<FooModuleFacade>() {
     *         override fun createComponent(context: Context): FooModuleFacade =
     *             delegateCreation(context, "module.foo.FooModuleFacadeFactory")
     *     }
     * }
     * ```
     *
     * ```
     * // "foo" module
     * package module.foo
     *
     * class FooModuleFacadeFactory: DelegatedComponentFactory<FooModuleFacade>() {
     *     override fun createComponent(context: Context): FooModuleFacade =
     *         FooModuleFacadeImpl(context)
     * }
     *
     * internal class FooModuleFacadeImpl(private val context: Context) : FooModuleFacade {
     *
     *     override fun launchFooActivity() {
     *         // snip...
     *     }
     * }
     * ```
     *
     * @param context the application context.
     * @param delegatedFactoryClassName the binary name of a class that inherits
     * [DelegatedComponentFactory].
     * @throws FactoryDelegationException if it failed to find or instantiate the class for
     * [delegatedFactoryClassName].
     * @see DelegatedComponentFactory
     */
    protected fun delegateCreation(context: Context, delegatedFactoryClassName: String): T {
        val delegatedFactory = try {
            @Suppress("UNCHECKED_CAST")
            Class.forName(delegatedFactoryClassName, true, javaClass.classLoader).newInstance()
                as DelegatedComponentFactory<out T>
        } catch (e: Throwable) {
            throw FactoryDelegationException(e)
        }

        return delegatedFactory.create(context)
    }

    /**
     * Delegates the creation of a component to [ServiceLoader].
     *
     * This function instantiates a class specified in the `META-INF/services/<binary name of T>`
     * Java resource file, and calls its `init(context)` function if it implements
     * [ServiceLoaderComponent] interface, then returns it.
     * If multiple class names are specified in the resource file, the class with the largest
     * [ServiceLoaderComponent.loadPriority] value is selected.
     *
     * You can use this function to divide dependencies in a multi-module project.
     * Assumes that there are two modules "base" and "foo" such that "foo" depends on "base".
     * If you want to call some function of "foo" from "base", implement like this:
     * ```
     * // "base" module
     * package module.base.facades
     *
     * interface FooModuleFacade {
     *
     *     fun launchFooActivity()
     *
     *     companion object : ComponentFactory<FooModuleFacade>() {
     *         override fun createComponent(context: Context): FooModuleFacade =
     *             delegateToServiceLoader(context)
     *     }
     * }
     * ```
     *
     * ```
     * // "foo" module
     * package module.foo
     *
     * class FooModuleFacadeImpl : FooModuleFacade, ServiceLoaderComponent {
     *
     *     private lateinit var context: Context
     *
     *     override fun init(context: Context) {
     *         this.context = context
     *     }
     *
     *     override fun launchFooActivity() {
     *         // snip...
     *     }
     * }
     * ```
     *
     * ```
     * # META-INF/services/module.base.facades.FooModuleFacade
     * module.foo.FooModuleFacadeImpl
     * ```
     *
     * @param T the interface or abstract class of the component.
     * @param context the application context.
     * @throws FactoryDelegationException if it failed to load or instantiate the concrete class(es)
     * for [T].
     * @see ServiceLoaderComponent
     */
    protected inline fun <reified T : Any> delegateToServiceLoader(context: Context): T {
        val candidates = Sequence {
            // R8 (in AGP 3.5.0 or later) will rewrite the line below as follows:
            //   Arrays.asList(new T[] { new T1(), new T2(), ..., new Tn() }).iterator()
            // where T1, T2, ... Tn are specified in "META-INF/services/<binary name of T>".
            ServiceLoader.load(T::class.java, T::class.java.classLoader).iterator()
        }
        return loadServiceLoaderComponent(context, candidates)
    }

    @PublishedApi
    internal fun <T : Any> loadServiceLoaderComponent(
        context: Context,
        candidates: Sequence<T>
    ): T {
        val component = try {
            candidates.maxBy { (it as? ServiceLoaderComponent)?.loadPriority ?: Int.MIN_VALUE }
        } catch (e: Throwable) {
            throw FactoryDelegationException(e)
        } ?: throw FactoryDelegationException("Service implementation is not found.")

        if (component is ServiceLoaderComponent) {
            component.init(context)
        }
        return component
    }

    internal fun create(context: Context): T = createComponent(context)
}
