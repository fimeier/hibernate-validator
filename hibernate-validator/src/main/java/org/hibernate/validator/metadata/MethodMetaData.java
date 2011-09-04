/*
* JBoss, Home of Professional Open Source
* Copyright 2011, Red Hat, Inc. and/or its affiliates, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.hibernate.validator.metadata;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.validation.ConstraintDeclarationException;
import javax.validation.Valid;

import org.hibernate.validator.metadata.constrained.ConstrainedElement;
import org.hibernate.validator.metadata.constrained.ConstrainedElement.ConstrainedElementKind;
import org.hibernate.validator.metadata.constrained.ConstrainedMethod;
import org.hibernate.validator.metadata.constrained.ConstrainedParameter;
import org.hibernate.validator.metadata.location.MethodConstraintLocation;
import org.hibernate.validator.util.ReflectionHelper;

import static org.hibernate.validator.util.CollectionHelper.newArrayList;
import static org.hibernate.validator.util.CollectionHelper.newHashSet;

/**
 * <p>
 * An aggregated view of the constraint related meta data for a given method and
 * all the methods in the inheritance hierarchy which it overrides or
 * implements.
 * </p>
 * <p>
 * Instances are retrieved by creating a {@link Builder} and adding all required
 * {@link ConstrainedMethod} objects to it. Instances are read-only after creation.
 * </p>
 *
 * @author Gunnar Morling
 */
public class MethodMetaData extends AbstractConstraintMetaData {

	private final Method rootMethod;

//	private final Map<Class<?>, ConstrainedMethod> metaDataByDefiningType;

	private final boolean isConstrained;

	private final List<ParameterMetaData> parameterMetaData;

	/**
	 * A declaration exception in case this method contains any illegal method
	 * parameter constraints. Such illegal parameter constraints shall not
	 * hinder standard bean/property validation as defined by the Bean
	 * Validation API. Therefore this exception is created when building up the
	 * meta data for validated beans, but it will only be thrown by the
	 * validation engine when actually a method validation is performed.
	 */
	private final ConstraintDeclarationException parameterConstraintDeclarationException;

	private MethodMetaData(
			Builder builder,
			Set<MetaConstraint<?>> returnValueConstraints,
			List<ParameterMetaData> parameterMetaData,
			ConstraintDeclarationException parameterConstraintDeclarationException,
			boolean isCascading,
			boolean isConstrained) {

		super( returnValueConstraints, ConstraintMetaDataKind.METHOD, isCascading );

//		metaDataByDefiningType = Collections.unmodifiableMap( builder.metaDataByDefiningType );
		this.isConstrained = isConstrained;
		rootMethod = builder.location.getMember();

		this.parameterMetaData = Collections.unmodifiableList( parameterMetaData );
		this.parameterConstraintDeclarationException = parameterConstraintDeclarationException;
	}

	/**
	 * Creates new {@link MethodMetaData} instances.
	 *
	 * @author Gunnar Morling
	 * @author Kevin Pollet - SERLI - (kevin.pollet@serli.com)
	 */
	public static class Builder extends MetaDataBuilder {

		private Set<ConstrainedMethod> constrainedMethods = newHashSet();

		private MethodConstraintLocation location;

		private final Set<MetaConstraint<?>> returnValueConstraints = newHashSet();

		private boolean isCascading;

		private boolean isConstrained;

		/**
		 * Creates a new builder based on the given method meta data.
		 *
		 * @param constrainedMethod The base method for this builder. This is the lowest
		 * method with a given signature within a type hierarchy.
		 */
		public Builder(ConstrainedMethod constrainedMethod) {

			location = constrainedMethod.getLocation();
			constrainedMethods.add( constrainedMethod );
			isCascading = constrainedMethod.isCascading();
			isConstrained = constrainedMethod.isConstrained();
			returnValueConstraints.addAll( constrainedMethod.getConstraints() );
		}

		/**
		 * {@inheritDoc}
		 */
		public boolean accepts(ConstrainedElement metaData) {

			return
					metaData.getConstrainedElementKind() == ConstrainedElementKind.METHOD &&
							ReflectionHelper.haveSameSignature(
									location.getMethod(),
									( (ConstrainedMethod) metaData ).getLocation().getMethod()
							);
		}

		/**
		 * {@inheritDoc}
		 */
		public void add(ConstrainedElement constrainedElement) {

			ConstrainedMethod metaData = (ConstrainedMethod) constrainedElement;

			constrainedMethods.add( metaData );

			//use the lowest method found in the hierarchy for this aggregation
			if ( location.getMethod()
					.getDeclaringClass()
					.isAssignableFrom( metaData.getLocation().getMethod().getDeclaringClass() ) ) {
				location = metaData.getLocation();
			}

			isCascading = isCascading || metaData.isCascading();
			isConstrained = isConstrained || metaData.isConstrained();
			returnValueConstraints.addAll( metaData.getConstraints() );
		}

		/**
		 * {@inheritDoc}
		 */
		public MethodMetaData build() {
			return
					new MethodMetaData(
							this,
							returnValueConstraints,
							findParameterMetaData(),
							checkParameterConstraints(),
							isCascading,
							isConstrained
					);
		}

		/**
		 * Finds the one method from the underlying hierarchy with parameter
		 * constraints. If no method in the hierarchy is parameter constrained,
		 * the parameter meta data from this builder's base method is returned.
		 *
		 * @return The parameter meta data for this builder's method.
		 */
		private List<ParameterMetaData> findParameterMetaData() {

			List<ParameterMetaData.Builder> parameterBuilders = null;

			for ( ConstrainedMethod oneMethod : constrainedMethods ) {

				if ( parameterBuilders == null ) {
					parameterBuilders = newArrayList();

					for ( ConstrainedParameter oneParameter : oneMethod.getAllParameterMetaData() ) {
						parameterBuilders.add( new ParameterMetaData.Builder( oneParameter ) );
					}
				}
				else {
					int i = 0;
					for ( ConstrainedParameter oneParameter : oneMethod.getAllParameterMetaData() ) {
						parameterBuilders.get( i ).add( oneParameter );
						i++;
					}
				}
			}

			List<ParameterMetaData> parameterMetaDatas = newArrayList();

			for ( ParameterMetaData.Builder oneBuilder : parameterBuilders ) {
				parameterMetaDatas.add( oneBuilder.build() );
			}

			return parameterMetaDatas;
		}

		/**
		 * Checks that there are no invalid parameter constraints defined at
		 * this builder's methods.
		 *
		 * @return A {@link ConstraintDeclarationException} describing the first
		 *         illegal method parameter constraint found or {@code null}, if
		 *         the methods of this builder have no such illegal constraints.
		 */
		private ConstraintDeclarationException checkParameterConstraints() {

			Set<ConstrainedMethod> methodsWithParameterConstraints = getMethodsWithParameterConstraints(
					constrainedMethods
			);

			if ( methodsWithParameterConstraints.isEmpty() ) {
				return null;
			}
			Set<Class<?>> definingTypes = newHashSet();

			for ( ConstrainedMethod constrainedMethod : methodsWithParameterConstraints ) {
				definingTypes.add( constrainedMethod.getLocation().getBeanClass() );
			}

			if ( definingTypes.size() > 1 ) {
				return new ConstraintDeclarationException(
						"Only the root method of an overridden method in an inheritance hierarchy may be annotated with parameter constraints, " +
								"but there are parameter constraints defined at all of the following overridden methods: " +
								methodsWithParameterConstraints
				);
			}

			ConstrainedMethod constrainedMethod = methodsWithParameterConstraints.iterator().next();

			for ( ConstrainedMethod oneMethod : constrainedMethods ) {

				if ( !constrainedMethod.getLocation().getBeanClass()
						.isAssignableFrom( oneMethod.getLocation().getBeanClass() ) ) {
					return new ConstraintDeclarationException(
							"Only the root method of an overridden method in an inheritance hierarchy may be annotated with parameter constraints. " +
									"The following method itself has no parameter constraints but it is not defined on a sub-type of " +
									constrainedMethod.getLocation().getBeanClass() + ": " + oneMethod
					);
				}
			}

			return null;
		}

		/**
		 * Returns a set with those methods from the given pile of methods that have
		 * at least one constrained parameter or at least one parameter annotated
		 * with {@link Valid}.
		 *
		 * @param methods The methods to search in.
		 *
		 * @return A set with constrained methods. May be empty, but never null.
		 */
		private Set<ConstrainedMethod> getMethodsWithParameterConstraints(Iterable<ConstrainedMethod> methods) {
			Set<ConstrainedMethod> theValue = newHashSet();

			for ( ConstrainedMethod oneMethod : methods ) {
				if ( oneMethod.hasParameterConstraints() ) {
					theValue.add( oneMethod );
				}
			}

			return theValue;
		}

	}

	/**
	 * <p>
	 * Checks the parameter constraints of this method for correctness.
	 * </p>
	 * <p>
	 * The following rules apply for this check:
	 * </p>
	 * <ul>
	 * <li>Only the root method of an overridden method in an inheritance
	 * hierarchy may be annotated with parameter constraints in order to avoid
	 * the strengthening of a method's preconditions by additional parameter
	 * constraints defined at sub-types. If the root method itself has no
	 * parameter constraints, also no parameter constraints may be added in
	 * sub-types.</li>
	 * <li>If there are multiple root methods for an method in an inheritance
	 * hierarchy (e.g. by implementing two interfaces defining the same method)
	 * no parameter constraints for this method are allowed at all in order to
	 * avoid a strengthening of a method's preconditions in parallel types.</li>
	 * </ul>
	 *
	 * @throws ConstraintDeclarationException In case the represented method has an illegal parameter
	 * constraint.
	 */
	public void assertCorrectnessOfMethodParameterConstraints() throws ConstraintDeclarationException {

		if ( parameterConstraintDeclarationException != null ) {
			throw parameterConstraintDeclarationException;
		}
	}

	/**
	 * Returns meta data for the specified parameter of the represented method.
	 *
	 * @return Meta data for the specified parameter. Will never be
	 *         <code>null</code>.
	 */
	public ParameterMetaData getParameterMetaData(int parameterIndex) {

		if ( parameterIndex < 0 || parameterIndex > parameterMetaData.size() - 1 ) {
			throw new IllegalArgumentException( "Method " + getName() + " doesn't have a parameter with index " + parameterIndex );
		}

		return parameterMetaData.get( parameterIndex );
	}

	/**
	 * Returns meta data for all parameters of the represented method.
	 *
	 * @return A list with parameter meta data. The length corresponds to the
	 *         number of parameters of the method represented by this meta data
	 *         object, so an empty list may be returned (in case of a
	 *         parameterless method), but never <code>null</code>.
	 */
	public List<ParameterMetaData> getAllParameterMetaData() {
		return parameterMetaData;
	}

	/**
	 * Whether the represented method itself or any of the method's up in the
	 * inheritance hierarchy which it overrides/implements is constrained.
	 *
	 * @return <code>True</code>, if this method is constrained by any means,
	 *         <code>false</code> otherwise.
	 */
	public boolean isConstrained() {
		return isConstrained;
	}

	public String getName() {
		return rootMethod.getName();
	}

	public Class<?> getReturnType() {
		return rootMethod.getReturnType();
	}

	public Class<?>[] getParameterTypes() {
		return rootMethod.getParameterTypes();
	}

	@Override
	public String toString() {

		StringBuilder parameterBuilder = new StringBuilder();

		for ( Class<?> oneParameterType : getParameterTypes() ) {
			parameterBuilder.append( oneParameterType.getSimpleName() );
			parameterBuilder.append( ", " );
		}

		String parameters =
				parameterBuilder.length() > 0 ?
						parameterBuilder.substring( 0, parameterBuilder.length() - 2 ) :
						parameterBuilder.toString();

		return "MethodMetaData [method=" + getReturnType().getSimpleName() + " " + getName() + "(" + parameters + "), isCascading=" + isCascading() + ", isConstrained="
				+ isConstrained() + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result
				+ ( ( rootMethod == null ) ? 0 : rootMethod.hashCode() );
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if ( this == obj ) {
			return true;
		}
		if ( !super.equals( obj ) ) {
			return false;
		}
		if ( getClass() != obj.getClass() ) {
			return false;
		}
		MethodMetaData other = (MethodMetaData) obj;
		if ( rootMethod == null ) {
			if ( other.rootMethod != null ) {
				return false;
			}
		}
		else if ( !rootMethod.equals( other.rootMethod ) ) {
			return false;
		}
		return true;
	}

}
