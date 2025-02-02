/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
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

package org.optaplanner.core.impl.score.stream.drools;

import static org.drools.model.DSL.globalOf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.drools.model.Global;
import org.drools.model.impl.ModelImpl;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.uni.UniConstraintStream;
import org.optaplanner.core.impl.domain.constraintweight.descriptor.ConstraintConfigurationDescriptor;
import org.optaplanner.core.impl.domain.solution.descriptor.SolutionDescriptor;
import org.optaplanner.core.impl.score.director.drools.DroolsScoreDirector;
import org.optaplanner.core.impl.score.holder.AbstractScoreHolder;
import org.optaplanner.core.impl.score.stream.ConstraintSessionFactory;
import org.optaplanner.core.impl.score.stream.InnerConstraintFactory;
import org.optaplanner.core.impl.score.stream.drools.uni.DroolsFromUniConstraintStream;

public final class DroolsConstraintFactory<Solution_> extends InnerConstraintFactory<Solution_> {

    private final SolutionDescriptor<Solution_> solutionDescriptor;
    private final String defaultConstraintPackage;
    private final DroolsVariableFactory variableFactory = new DroolsVariableFactoryImpl();

    public DroolsConstraintFactory(SolutionDescriptor<Solution_> solutionDescriptor) {
        this.solutionDescriptor = solutionDescriptor;
        ConstraintConfigurationDescriptor<Solution_> configurationDescriptor = solutionDescriptor
                .getConstraintConfigurationDescriptor();
        if (configurationDescriptor == null) {
            Package pack = solutionDescriptor.getSolutionClass().getPackage();
            defaultConstraintPackage = (pack == null) ? "" : pack.getName();
        } else {
            defaultConstraintPackage = configurationDescriptor.getConstraintPackage();
        }
    }

    @Override
    public <A> UniConstraintStream<A> fromUnfiltered(Class<A> fromClass) {
        assertValidFromType(fromClass);
        return new DroolsFromUniConstraintStream<>(this, fromClass);
    }

    // ************************************************************************
    // SessionFactory creation
    // ************************************************************************

    @Override
    public ConstraintSessionFactory<Solution_, ?> buildSessionFactory(Constraint[] constraints) {
        ModelImpl model = new ModelImpl();

        AbstractScoreHolder<?> scoreHolder = solutionDescriptor.getScoreDefinition()
                .buildScoreHolder(false);
        Class<? extends AbstractScoreHolder<?>> scoreHolderClass = (Class<? extends AbstractScoreHolder<?>>) scoreHolder
                .getClass();
        Package pack = solutionDescriptor.getSolutionClass().getPackage();
        Global<? extends AbstractScoreHolder<?>> scoreHolderGlobal = globalOf(scoreHolderClass,
                (pack == null) ? "" : pack.getName(),
                DroolsScoreDirector.GLOBAL_SCORE_HOLDER_KEY);
        model.addGlobal(scoreHolderGlobal);

        List<DroolsConstraint<Solution_>> droolsConstraintList = new ArrayList<>(constraints.length);
        Set<String> constraintIdSet = new HashSet<>(constraints.length);
        for (Constraint constraint : constraints) {
            if (constraint.getConstraintFactory() != this) {
                throw new IllegalStateException("The constraint (" + constraint.getConstraintId()
                        + ") must be created from the same constraintFactory.");
            }
            DroolsConstraint<Solution_> droolsConstraint = (DroolsConstraint<Solution_>) constraint;
            boolean added = constraintIdSet.add(droolsConstraint.getConstraintId());
            if (!added) {
                throw new IllegalStateException(
                        "There are 2 constraints with the same constraintName (" + droolsConstraint.getConstraintName()
                                + ") in the same constraintPackage (" + droolsConstraint.getConstraintPackage() + ").");
            }
            droolsConstraintList.add(droolsConstraint);
            model.addRule(droolsConstraint.buildRule(scoreHolderGlobal));
        }
        return new DroolsConstraintSessionFactory<>(solutionDescriptor, model, droolsConstraintList);
    }

    // ************************************************************************
    // Getters/setters
    // ************************************************************************

    public SolutionDescriptor<Solution_> getSolutionDescriptor() {
        return solutionDescriptor;
    }

    public DroolsVariableFactory getVariableFactory() {
        return variableFactory;
    }

    @Override
    public String getDefaultConstraintPackage() {
        return defaultConstraintPackage;
    }

}
