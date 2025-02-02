/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates.
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

package org.optaplanner.quarkus.deployment;

import static io.quarkus.deployment.annotations.ExecutionTime.STATIC_INIT;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Singleton;

import org.apache.commons.lang3.ObjectUtils;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.optaplanner.core.api.domain.common.DomainAccessType;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.score.calculator.EasyScoreCalculator;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.ConstraintStreamImplType;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.SolverManagerConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.optaplanner.core.impl.domain.solution.descriptor.SolutionDescriptor;
import org.optaplanner.quarkus.OptaPlannerBeanProvider;
import org.optaplanner.quarkus.OptaPlannerRecorder;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanGizmoAdaptor;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyBuildItem;
import io.quarkus.deployment.recording.RecorderContext;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.runtime.configuration.ConfigurationException;

class OptaPlannerProcessor {

    private static final Logger log = Logger.getLogger(OptaPlannerProcessor.class.getName());

    OptaPlannerBuildTimeConfig optaPlannerBuildTimeConfig;

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("optaplanner");
    }

    @BuildStep
    HotDeploymentWatchedFileBuildItem watchSolverConfigXml() {
        String solverConfigXML = optaPlannerBuildTimeConfig.solverConfigXml
                .orElse(OptaPlannerBuildTimeConfig.DEFAULT_SOLVER_CONFIG_URL);
        return new HotDeploymentWatchedFileBuildItem(solverConfigXML);
    }

    @BuildStep
    HotDeploymentWatchedFileBuildItem watchConstraintsDrl() {
        String constraintsDrl =
                optaPlannerBuildTimeConfig.scoreDrl.orElse(OptaPlannerBuildTimeConfig.DEFAULT_CONSTRAINTS_DRL_URL);
        return new HotDeploymentWatchedFileBuildItem(constraintsDrl);
    }

    @BuildStep
    IndexDependencyBuildItem indexDependencyBuildItem() {
        // Add @PlanningEntity and other annotations in the Jandex index for Gizmo
        return new IndexDependencyBuildItem("org.optaplanner", "optaplanner-core");
    }

    @BuildStep
    @Record(STATIC_INIT)
    void recordAndRegisterBeans(OptaPlannerRecorder recorder, RecorderContext recorderContext,
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<ReflectiveHierarchyBuildItem> reflectiveHierarchyClass,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<GeneratedBeanBuildItem> generatedBeans,
            BuildProducer<GeneratedClassBuildItem> generatedClasses,
            BuildProducer<BytecodeTransformerBuildItem> transformers) {
        IndexView indexView = combinedIndex.getIndex();

        // Only skip this extension if everything is missing. Otherwise, if some parts are missing, fail fast later.
        if (indexView.getAnnotations(DotNames.PLANNING_SOLUTION).isEmpty()
                && indexView.getAnnotations(DotNames.PLANNING_ENTITY).isEmpty()) {
            log.warn("Skipping OptaPlanner extension because there are no " + PlanningSolution.class.getSimpleName()
                    + " or " + PlanningEntity.class.getSimpleName() + " annotated classes."
                    + "\nIf your domain classes are located in a dependency of this project, maybe try generating"
                    + " the Jandex index by using the jandex-maven-plugin in that dependency, or by adding"
                    + "application.properties entries (quarkus.index-dependency.<name>.group-id"
                    + " and quarkus.index-dependency.<name>.artifact-id).");
            return;
        }

        // Quarkus extensions must always use getContextClassLoader()
        // Internally, OptaPlanner defaults the ClassLoader to getContextClassLoader() too
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        SolverConfig solverConfig;
        if (optaPlannerBuildTimeConfig.solverConfigXml.isPresent()) {
            String solverConfigXML = optaPlannerBuildTimeConfig.solverConfigXml.get();
            if (classLoader.getResource(solverConfigXML) == null) {
                throw new ConfigurationException("Invalid quarkus.optaplanner.solverConfigXML property ("
                        + solverConfigXML + "): that classpath resource does not exist.");
            }
            solverConfig = SolverConfig.createFromXmlResource(solverConfigXML);
        } else if (classLoader.getResource(OptaPlannerBuildTimeConfig.DEFAULT_SOLVER_CONFIG_URL) != null) {
            solverConfig = SolverConfig.createFromXmlResource(
                    OptaPlannerBuildTimeConfig.DEFAULT_SOLVER_CONFIG_URL);
        } else {
            solverConfig = new SolverConfig();
        }

        applySolverProperties(recorderContext, indexView, solverConfig);
        assertNoMemberAnnotationWithoutClassAnnotation(indexView);

        if (solverConfig.getSolutionClass() != null) {
            // Need to register even when using GIZMO so annotations are preserved
            Type jandexType = Type.create(DotName.createSimple(solverConfig.getSolutionClass().getName()), Type.Kind.CLASS);
            reflectiveHierarchyClass.produce(new ReflectiveHierarchyBuildItem.Builder()
                    .type(jandexType)
                    // Ignore only the packages from optaplanner-core
                    // (Can cause a hard to diagnoise issue when creating a test/example
                    // in the package "org.optaplanner").
                    .ignoreTypePredicate(
                            dotName -> ReflectiveHierarchyBuildItem.DefaultIgnoreTypePredicate.INSTANCE.test(dotName)
                                    || dotName.toString().startsWith("org.optaplanner.api")
                                    || dotName.toString().startsWith("org.optaplanner.config")
                                    || dotName.toString().startsWith("org.optaplanner.impl"))
                    .build());
        }

        Set<Class<?>> reflectiveClassSet = new LinkedHashSet<>();
        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = solverConfig.getScoreDirectorFactoryConfig();
        if (scoreDirectorFactoryConfig != null) {
            if (scoreDirectorFactoryConfig.getEasyScoreCalculatorClass() != null) {
                reflectiveClassSet.add(scoreDirectorFactoryConfig.getEasyScoreCalculatorClass());
            }
            if (scoreDirectorFactoryConfig.getConstraintProviderClass() != null) {
                reflectiveClassSet.add(scoreDirectorFactoryConfig.getConstraintProviderClass());
            }
            if (scoreDirectorFactoryConfig.getIncrementalScoreCalculatorClass() != null) {
                reflectiveClassSet.add(scoreDirectorFactoryConfig.getIncrementalScoreCalculatorClass());
            }
        }

        registerClassesFromAnnotations(indexView, reflectiveClassSet);
        generateConstraintVerifier(solverConfig, syntheticBeanBuildItemBuildProducer);
        generateDomainAccessors(solverConfig, indexView, generatedBeans, generatedClasses, transformers,
                reflectiveClassSet);

        SolverManagerConfig solverManagerConfig = new SolverManagerConfig();
        optaPlannerBuildTimeConfig.solverManager.parallelSolverCount.ifPresent(solverManagerConfig::setParallelSolverCount);

        syntheticBeanBuildItemBuildProducer.produce(SyntheticBeanBuildItem.configure(SolverConfig.class)
                .scope(Singleton.class)
                .defaultBean()
                .supplier(recorder.solverConfigSupplier(solverConfig)).done());

        syntheticBeanBuildItemBuildProducer.produce(SyntheticBeanBuildItem.configure(SolverManagerConfig.class)
                .scope(Singleton.class)
                .defaultBean()
                .supplier(recorder.solverManagerConfig(solverManagerConfig)).done());

        additionalBeans.produce(new AdditionalBeanBuildItem(OptaPlannerBeanProvider.class));
    }

    private void generateConstraintVerifier(SolverConfig solverConfig,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer) {
        if (solverConfig.getScoreDirectorFactoryConfig().getConstraintProviderClass() != null &&
                isClassDefined(DotNames.CONSTRAINT_VERIFIER.toString())) {
            final Class<?> constraintProviderClass = solverConfig.getScoreDirectorFactoryConfig().getConstraintProviderClass();
            final Class<?> planningSolutionClass = solverConfig.getSolutionClass();
            final List<Class<?>> planningEntityClasses = solverConfig.getEntityClassList();
            final ConstraintStreamImplType constraintStreamImplType =
                    ObjectUtils.defaultIfNull(solverConfig.getScoreDirectorFactoryConfig().getConstraintStreamImplType(),
                            ConstraintStreamImplType.DROOLS);
            syntheticBeanBuildItemBuildProducer.produce(SyntheticBeanBuildItem.configure(DotNames.CONSTRAINT_VERIFIER)
                    .scope(Singleton.class)
                    .creator(methodCreator -> {
                        ResultHandle constraintProviderResultHandle =
                                methodCreator.newInstance(MethodDescriptor.ofConstructor(constraintProviderClass));
                        ResultHandle planningSolutionClassResultHandle = methodCreator.loadClass(planningSolutionClass);

                        ResultHandle planningEntityClassesResultHandle =
                                methodCreator.newArray(Class.class, planningEntityClasses.size());
                        for (int i = 0; i < planningEntityClasses.size(); i++) {
                            ResultHandle planningEntityClassResultHandle =
                                    methodCreator.loadClass(planningEntityClasses.get(i));
                            methodCreator.writeArrayValue(planningEntityClassesResultHandle, i,
                                    planningEntityClassResultHandle);
                        }

                        // Got incompatible class change error when trying to invoke static method on
                        // ConstraintVerifier.build(ConstraintProvider, Class, Class...)
                        ResultHandle solutionDescriptorResultHandle = methodCreator.invokeStaticMethod(
                                MethodDescriptor.ofMethod(SolutionDescriptor.class, "buildSolutionDescriptor",
                                        SolutionDescriptor.class, Class.class, Class[].class),
                                planningSolutionClassResultHandle, planningEntityClassesResultHandle);
                        ResultHandle constraintVerifierResultHandle = methodCreator.newInstance(
                                MethodDescriptor.ofConstructor(
                                        "org.optaplanner.test.impl.score.stream.DefaultConstraintVerifier",
                                        ConstraintProvider.class, SolutionDescriptor.class),
                                constraintProviderResultHandle, solutionDescriptorResultHandle);

                        ResultHandle constraintStreamImplTypeResultHandle = methodCreator.load(constraintStreamImplType);
                        constraintVerifierResultHandle = methodCreator.invokeInterfaceMethod(
                                MethodDescriptor.ofMethod(DotNames.CONSTRAINT_VERIFIER.toString(),
                                        "withConstraintStreamImplType",
                                        DotNames.CONSTRAINT_VERIFIER.toString(),
                                        ConstraintStreamImplType.class),
                                constraintVerifierResultHandle, constraintStreamImplTypeResultHandle);
                        methodCreator.returnValue(constraintVerifierResultHandle);
                    })
                    .addType(ParameterizedType.create(DotNames.CONSTRAINT_VERIFIER,
                            new Type[] {
                                    Type.create(DotName.createSimple(constraintProviderClass.getName()), Type.Kind.CLASS),
                                    Type.create(DotName.createSimple(planningSolutionClass.getName()), Type.Kind.CLASS)
                            }, null))
                    .defaultBean()
                    .done());
        }
    }

    private void applySolverProperties(RecorderContext recorderContext,
            IndexView indexView, SolverConfig solverConfig) {
        if (solverConfig.getSolutionClass() == null) {
            solverConfig.setSolutionClass(findSolutionClass(recorderContext, indexView));
        }
        if (solverConfig.getEntityClassList() == null) {
            solverConfig.setEntityClassList(findEntityClassList(recorderContext, indexView));
        }
        applyScoreDirectorFactoryProperties(indexView, solverConfig);
        optaPlannerBuildTimeConfig.solver.environmentMode.ifPresent(solverConfig::setEnvironmentMode);
        optaPlannerBuildTimeConfig.solver.daemon.ifPresent(solverConfig::setDaemon);
        optaPlannerBuildTimeConfig.solver.moveThreadCount.ifPresent(solverConfig::setMoveThreadCount);
        optaPlannerBuildTimeConfig.solver.domainAccessType.ifPresent(solverConfig::setDomainAccessType);
        if (solverConfig.getDomainAccessType() == null) {
            solverConfig.setDomainAccessType(DomainAccessType.REFLECTION);
        }
        applyTerminationProperties(solverConfig);
    }

    private Class<?> findSolutionClass(RecorderContext recorderContext, IndexView indexView) {
        Collection<AnnotationInstance> annotationInstances = indexView.getAnnotations(DotNames.PLANNING_SOLUTION);
        if (annotationInstances.size() > 1) {
            throw new IllegalStateException("Multiple classes (" + convertAnnotationInstancesToString(annotationInstances)
                    + ") found with a @" + PlanningSolution.class.getSimpleName() + " annotation.");
        }
        if (annotationInstances.isEmpty()) {
            throw new IllegalStateException("No classes (" + convertAnnotationInstancesToString(annotationInstances)
                    + ") found with a @" + PlanningSolution.class.getSimpleName() + " annotation.");
        }
        AnnotationTarget solutionTarget = annotationInstances.iterator().next().target();
        if (solutionTarget.kind() != AnnotationTarget.Kind.CLASS) {
            throw new IllegalStateException("A target (" + solutionTarget
                    + ") with a @" + PlanningSolution.class.getSimpleName() + " must be a class.");
        }
        return convertClassInfoToClass(solutionTarget.asClass());
    }

    private List<Class<?>> findEntityClassList(RecorderContext recorderContext, IndexView indexView) {
        Collection<AnnotationInstance> annotationInstances = indexView.getAnnotations(DotNames.PLANNING_ENTITY);
        if (annotationInstances.isEmpty()) {
            throw new IllegalStateException("No classes (" + convertAnnotationInstancesToString(annotationInstances)
                    + ") found with a @" + PlanningEntity.class.getSimpleName() + " annotation.");
        }
        List<AnnotationTarget> targetList = annotationInstances.stream()
                .map(AnnotationInstance::target)
                .collect(Collectors.toList());
        if (targetList.stream().anyMatch(target -> target.kind() != AnnotationTarget.Kind.CLASS)) {
            throw new IllegalStateException("All targets (" + targetList
                    + ") with a @" + PlanningEntity.class.getSimpleName() + " must be a class.");
        }
        return targetList.stream()
                .map(target -> (Class<?>) convertClassInfoToClass(target.asClass()))
                .collect(Collectors.toList());
    }

    private void assertNoMemberAnnotationWithoutClassAnnotation(IndexView indexView) {
        Collection<AnnotationInstance> optaplannerFieldAnnotations = new HashSet<>();

        for (DotName annotationName : DotNames.PLANNING_ENTITY_FIELD_ANNOTATIONS) {
            optaplannerFieldAnnotations.addAll(indexView.getAnnotations(annotationName));
        }

        for (AnnotationInstance annotationInstance : optaplannerFieldAnnotations) {
            AnnotationTarget annotationTarget = annotationInstance.target();
            ClassInfo declaringClass;
            String prefix;
            switch (annotationTarget.kind()) {
                case FIELD:
                    prefix = "The field (" + annotationTarget.asField().name() + ") ";
                    declaringClass = annotationTarget.asField().declaringClass();
                    break;
                case METHOD:
                    prefix = "The method (" + annotationTarget.asMethod().name() + ") ";
                    declaringClass = annotationTarget.asMethod().declaringClass();
                    break;
                default:
                    throw new IllegalStateException(
                            "Member annotation @" + annotationInstance.name().withoutPackagePrefix() + " is on ("
                                    + annotationTarget +
                                    "), which is an invalid target type (" + annotationTarget.kind() +
                                    ") for @" + annotationInstance.name().withoutPackagePrefix() + ".");
            }

            if (!declaringClass.annotations().containsKey(DotNames.PLANNING_ENTITY)) {
                throw new IllegalStateException(prefix + "with a @" +
                        annotationInstance.name().withoutPackagePrefix() +
                        " annotation is in a class (" + declaringClass.name()
                        + ") that does not have a @" + PlanningEntity.class.getSimpleName() +
                        " annotation.\n" +
                        "Maybe add a @" + PlanningEntity.class.getSimpleName() +
                        " annotation on the class (" + declaringClass.name() + ").");
            }
        }
    }

    private void registerClassesFromAnnotations(IndexView indexView, Set<Class<?>> reflectiveClassSet) {
        for (DotNames.BeanDefiningAnnotations beanDefiningAnnotation : DotNames.BeanDefiningAnnotations.values()) {
            for (AnnotationInstance annotationInstance : indexView
                    .getAnnotations(beanDefiningAnnotation.getAnnotationDotName())) {
                for (String parameterName : beanDefiningAnnotation.getParameterNames()) {
                    AnnotationValue value = annotationInstance.value(parameterName);

                    // We don't care about the default/null type.
                    if (value != null) {
                        Type type = value.asClass();
                        try {
                            Class<?> beanClass = Class.forName(type.name().toString(), false,
                                    Thread.currentThread().getContextClassLoader());
                            reflectiveClassSet.add(beanClass);
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException("Cannot find bean class (" + type.name() +
                                    ") referenced in annotation (" + annotationInstance + ").");
                        }
                    }
                }
            }
        }
    }

    protected void applyScoreDirectorFactoryProperties(IndexView indexView, SolverConfig solverConfig) {
        Optional<String> constraintsDrlFromProperty = constraintsDrl();
        Optional<String> defaultConstraintsDrl = defaultConstraintsDrl();
        Optional<String> effectiveConstraintsDrl = constraintsDrlFromProperty.map(Optional::of).orElse(defaultConstraintsDrl);
        if (solverConfig.getScoreDirectorFactoryConfig() == null) {
            ScoreDirectorFactoryConfig scoreDirectorFactoryConfig =
                    defaultScoreDirectoryFactoryConfig(indexView, effectiveConstraintsDrl);
            solverConfig.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        } else {
            ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = solverConfig.getScoreDirectorFactoryConfig();
            if (constraintsDrlFromProperty.isPresent()) {
                scoreDirectorFactoryConfig.setScoreDrlList(Collections.singletonList(constraintsDrlFromProperty.get()));
            } else {
                if (scoreDirectorFactoryConfig.getScoreDrlList() == null) {
                    defaultConstraintsDrl.ifPresent(resolvedConstraintsDrl -> scoreDirectorFactoryConfig
                            .setScoreDrlList(Collections.singletonList(resolvedConstraintsDrl)));
                }
            }
        }

        if (solverConfig.getScoreDirectorFactoryConfig().getScoreDrlList() != null) {
            boolean isDroolsDynamicPresent = isClassDefined("org.drools.dynamic.DynamicServiceRegistrySupplier");
            if (!isDroolsDynamicPresent) {
                throw new IllegalStateException(
                        "Using scoreDRL in Quarkus, but the dependency drools-core-dynamic is not on the classpath.\n"
                                + "Maybe add the dependency org.kie.kogito:drools-core-dynamic and exclude the dependency"
                                + " org.kie.kogito:drools-core-static."
                                + "\nOr maybe use a " + ConstraintProvider.class.getSimpleName() + " instead of the scoreDRL.");
            }
        }
    }

    private boolean isClassDefined(String className) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    protected Optional<String> constraintsDrl() {
        if (optaPlannerBuildTimeConfig.scoreDrl.isPresent()) {
            String constraintsDrl = optaPlannerBuildTimeConfig.scoreDrl.get();
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader.getResource(constraintsDrl) == null) {
                throw new IllegalStateException("Invalid " + OptaPlannerBuildTimeConfig.CONSTRAINTS_DRL_PROPERTY
                        + " property (" + constraintsDrl + "): that classpath resource does not exist.");
            }
        }
        return optaPlannerBuildTimeConfig.scoreDrl;
    }

    protected Optional<String> defaultConstraintsDrl() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader.getResource(OptaPlannerBuildTimeConfig.DEFAULT_CONSTRAINTS_DRL_URL) != null
                ? Optional.of(OptaPlannerBuildTimeConfig.DEFAULT_CONSTRAINTS_DRL_URL)
                : Optional.empty();
    }

    private ScoreDirectorFactoryConfig defaultScoreDirectoryFactoryConfig(IndexView indexView, Optional<String> constrainsDrl) {
        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setEasyScoreCalculatorClass(
                findImplementingClass(DotNames.EASY_SCORE_CALCULATOR, indexView));
        scoreDirectorFactoryConfig.setConstraintProviderClass(
                findImplementingClass(DotNames.CONSTRAINT_PROVIDER, indexView));
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(
                findImplementingClass(DotNames.INCREMENTAL_SCORE_CALCULATOR, indexView));
        constrainsDrl.ifPresent(value -> scoreDirectorFactoryConfig.setScoreDrlList(Collections.singletonList(value)));
        if (scoreDirectorFactoryConfig.getEasyScoreCalculatorClass() == null
                && scoreDirectorFactoryConfig.getConstraintProviderClass() == null
                && scoreDirectorFactoryConfig.getIncrementalScoreCalculatorClass() == null
                && scoreDirectorFactoryConfig.getScoreDrlList() == null) {
            throw new IllegalStateException("No classes found that implement "
                    + EasyScoreCalculator.class.getSimpleName() + ", "
                    + ConstraintProvider.class.getSimpleName() + " or "
                    + IncrementalScoreCalculator.class.getSimpleName() + ".\n"
                    + "Neither was a property " + OptaPlannerBuildTimeConfig.CONSTRAINTS_DRL_PROPERTY + " defined, nor a "
                    + OptaPlannerBuildTimeConfig.DEFAULT_CONSTRAINTS_DRL_URL + " resource found.\n");
        }
        return scoreDirectorFactoryConfig;
    }

    private <T> Class<? extends T> findImplementingClass(DotName targetDotName, IndexView indexView) {
        Collection<ClassInfo> classInfos = indexView.getAllKnownImplementors(targetDotName);
        if (classInfos.size() > 1) {
            throw new IllegalStateException("Multiple classes (" + convertClassInfosToString(classInfos)
                    + ") found that implement the interface " + targetDotName + ".");
        }
        if (classInfos.isEmpty()) {
            return null;
        }
        ClassInfo classInfo = classInfos.iterator().next();
        return convertClassInfoToClass(classInfo);
    }

    private void applyTerminationProperties(SolverConfig solverConfig) {
        TerminationConfig terminationConfig = solverConfig.getTerminationConfig();
        if (terminationConfig == null) {
            terminationConfig = new TerminationConfig();
            solverConfig.setTerminationConfig(terminationConfig);
        }
        optaPlannerBuildTimeConfig.solver.termination.spentLimit.ifPresent(terminationConfig::setSpentLimit);
        optaPlannerBuildTimeConfig.solver.termination.unimprovedSpentLimit
                .ifPresent(terminationConfig::setUnimprovedSpentLimit);
        optaPlannerBuildTimeConfig.solver.termination.bestScoreLimit.ifPresent(terminationConfig::setBestScoreLimit);
    }

    private String convertAnnotationInstancesToString(Collection<AnnotationInstance> annotationInstances) {
        return "[" + annotationInstances.stream().map(instance -> instance.target().toString())
                .collect(Collectors.joining(", ")) + "]";
    }

    private String convertClassInfosToString(Collection<ClassInfo> classInfos) {
        return "[" + classInfos.stream().map(instance -> instance.name().toString())
                .collect(Collectors.joining(", ")) + "]";
    }

    private <T> Class<? extends T> convertClassInfoToClass(ClassInfo classInfo) {
        String className = classInfo.name().toString();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            return (Class<? extends T>) classLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The class (" + className
                    + ") cannot be created during deployment.", e);
        }
    }

    private void generateDomainAccessors(SolverConfig solverConfig, IndexView indexView,
            BuildProducer<GeneratedBeanBuildItem> generatedBeans,
            BuildProducer<GeneratedClassBuildItem> generatedClasses,
            BuildProducer<BytecodeTransformerBuildItem> transformers, Set<Class<?>> reflectiveClassSet) {
        ClassOutput classOutput = new GeneratedClassGizmoAdaptor(generatedClasses, true);
        ClassOutput beanClassOutput = new GeneratedBeanGizmoAdaptor(generatedBeans);
        ClassOutput debuggableClassOutput = (className, bytes) -> {
            final String DEBUG_CLASSES_DIR = "target/optaplanner-generated-classes";
            if (DEBUG_CLASSES_DIR != null) {
                Path pathToFile = Paths.get(DEBUG_CLASSES_DIR, className.replace('.', '/') + ".class");
                try {
                    Files.createDirectories(pathToFile.getParent());
                    Files.write(pathToFile, bytes);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to write generated class to file (" + pathToFile + ").", e);
                }
            }
            classOutput.write(className, bytes);
        };

        Set<String> generatedMemberAccessorsClassNameSet = new HashSet<>();
        Set<String> gizmoSolutionClonerClassNameSet = new HashSet<>();

        if (solverConfig.getDomainAccessType() == DomainAccessType.GIZMO) {
            Collection<AnnotationInstance> membersToGeneratedAccessorsFor = new ArrayList<>();

            for (DotName dotName : DotNames.GIZMO_MEMBER_ACCESSOR_ANNOTATIONS) {
                membersToGeneratedAccessorsFor.addAll(indexView.getAnnotations(dotName));
            }

            for (AnnotationInstance annotatedMember : membersToGeneratedAccessorsFor) {
                switch (annotatedMember.target().kind()) {
                    case FIELD: {
                        FieldInfo fieldInfo = annotatedMember.target().asField();
                        ClassInfo classInfo = fieldInfo.declaringClass();

                        if (!shouldIgnoreMember(classInfo)) {
                            try {
                                generatedMemberAccessorsClassNameSet
                                        .add(GizmoMemberAccessorEntityEnhancer.generateFieldAccessor(annotatedMember, indexView,
                                                debuggableClassOutput,
                                                classInfo, fieldInfo, transformers));
                            } catch (ClassNotFoundException | NoSuchFieldException e) {
                                throw new IllegalStateException("Fail to generate member accessor for field (" +
                                        fieldInfo.name() + ") of class " +
                                        classInfo.name().toString() + ".", e);
                            }
                        }
                        break;
                    }
                    case METHOD: {
                        MethodInfo methodInfo = annotatedMember.target().asMethod();
                        ClassInfo classInfo = methodInfo.declaringClass();

                        if (!shouldIgnoreMember(classInfo)) {
                            try {
                                generatedMemberAccessorsClassNameSet.add(
                                        GizmoMemberAccessorEntityEnhancer.generateMethodAccessor(annotatedMember, indexView,
                                                debuggableClassOutput,
                                                classInfo, methodInfo, transformers));
                            } catch (ClassNotFoundException | NoSuchMethodException e) {
                                throw new IllegalStateException("Failed to generate member accessor for the method (" +
                                        methodInfo.name() + ") of the class (" +
                                        classInfo.name() + ").", e);
                            }
                        }
                        break;
                    }
                    default: {
                        throw new IllegalStateException("The member (" + annotatedMember + ") is not on a field or method.");
                    }
                }
            }
            // Using REFLECTION domain access type so OptaPlanner doesn't try to generate GIZMO code
            SolutionDescriptor solutionDescriptor = SolutionDescriptor.buildSolutionDescriptor(DomainAccessType.REFLECTION,
                    solverConfig.getSolutionClass(), solverConfig.getEntityClassList());
            gizmoSolutionClonerClassNameSet.add(GizmoMemberAccessorEntityEnhancer.generateSolutionCloner(solutionDescriptor,
                    debuggableClassOutput,
                    indexView,
                    transformers));
        }

        GizmoMemberAccessorEntityEnhancer.generateGizmoInitializer(beanClassOutput, generatedMemberAccessorsClassNameSet,
                gizmoSolutionClonerClassNameSet);
        GizmoMemberAccessorEntityEnhancer.generateGizmoBeanFactory(beanClassOutput, reflectiveClassSet);
    }

    private boolean shouldIgnoreMember(ClassInfo declaringClass) {
        // SolutionDescriptor PLANNING_SCORE is also picked up as a candidate, which cause problems
        return declaringClass.name().toString().startsWith(SolutionDescriptor.class.getName());
    }

}
