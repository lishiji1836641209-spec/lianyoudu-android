#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/domain-tests
javac -d build/domain-tests app/src/main/java/com/lianyoudu/app/Domain.java app/src/main/java/com/lianyoudu/app/ExerciseCatalog.java app/src/main/java/com/lianyoudu/app/RouteTrack.java app/src/main/java/com/lianyoudu/app/EnergyMath.java app/src/main/java/com/lianyoudu/app/HeartReconnectPolicy.java tests/HeartReconnectTest.java tests/DomainTest.java tests/EnergyTest.java tests/CatalogTest.java tests/LearningTest.java app/src/main/java/com/lianyoudu/app/ExerciseLearning.java app/src/main/java/com/lianyoudu/app/ExerciseHistory.java app/src/main/java/com/lianyoudu/app/TrainingTemplates.java app/src/main/java/com/lianyoudu/app/ExerciseChoices.java
java -cp build/domain-tests com.lianyoudu.app.DomainTest

java -cp build/domain-tests com.lianyoudu.app.EnergyTest
java -cp build/domain-tests com.lianyoudu.app.CatalogTest

java -cp build/domain-tests com.lianyoudu.app.LearningTest

java -cp build/domain-tests com.lianyoudu.app.HeartReconnectTest
