package edu.neu.ccs.pyramid.application;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.neu.ccs.pyramid.configuration.Config;
import edu.neu.ccs.pyramid.dataset.*;
import edu.neu.ccs.pyramid.eval.*;
import edu.neu.ccs.pyramid.feature.TopFeatures;
import edu.neu.ccs.pyramid.feature_selection.FeatureDistribution;
import edu.neu.ccs.pyramid.multilabel_classification.MultiLabelPredictionAnalysis;
import edu.neu.ccs.pyramid.multilabel_classification.imlgb.*;
import edu.neu.ccs.pyramid.multilabel_classification.thresholding.MacroFMeasureTuner;
import edu.neu.ccs.pyramid.multilabel_classification.thresholding.TunedMarginalClassifier;
import edu.neu.ccs.pyramid.util.Serialization;
import edu.neu.ccs.pyramid.util.SetUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.time.StopWatch;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * imlgb
 * Created by chengli on 6/13/15.
 */
public class App2 {

    public static void main(String[] args) throws Exception{
        if (args.length !=1){
            throw new IllegalArgumentException("Please specify a properties file.");
        }

        Config config = new Config(args[0]);
        System.out.println(config);

        new File(config.getString("output.folder")).mkdirs();

        if (config.getBoolean("train")){
            train(config);
            report(config,config.getString("input.trainData"));
        }

        if (config.getBoolean("tune")){
            tuneForMacroF(config);
        }

        if (config.getBoolean("test")){
            report(config,config.getString("input.testData"));
        }
    }

    public static void main(Config config) throws Exception{
        new File(config.getString("output.folder")).mkdirs();

        if (config.getBoolean("train")){
            train(config);
            report(config,config.getString("input.trainData"));
        }

        if (config.getBoolean("test")){
            report(config,config.getString("input.testData"));
        }
    }

    static MultiLabelClfDataSet loadData(Config config, String dataName) throws Exception{
        File dataFile = new File(new File(config.getString("input.folder"),
                "data_sets"),dataName);
        MultiLabelClfDataSet dataSet = TRECFormat.loadMultiLabelClfDataSet(dataFile, DataSetType.ML_CLF_SPARSE,
                true);
        return dataSet;
    }

//    static MultiLabelClfDataSet loadTrainData(Config config) throws Exception{
//        String trainFile = new File(config.getString("input.folder"),
//                config.getString("input.trainData")).getAbsolutePath();
//        MultiLabelClfDataSet dataSet;
//
//        if (config.getBoolean("input.featureMatrix.sparse")){
//            dataSet= TRECFormat.loadMultiLabelClfDataSet(new File(trainFile), DataSetType.ML_CLF_SPARSE,
//                    true);
//        } else {
//            dataSet= TRECFormat.loadMultiLabelClfDataSet(new File(trainFile), DataSetType.ML_CLF_DENSE,
//                    true);
//        }
//
//        return dataSet;
//    }
//
//    static MultiLabelClfDataSet loadTestData(Config config) throws Exception{
//        String trainFile = new File(config.getString("input.folder"),
//                config.getString("input.testData")).getAbsolutePath();
//        MultiLabelClfDataSet dataSet;
//
//        if (config.getBoolean("input.featureMatrix.sparse")){
//            dataSet= TRECFormat.loadMultiLabelClfDataSet(new File(trainFile), DataSetType.ML_CLF_SPARSE,
//                    true);
//        } else {
//            dataSet= TRECFormat.loadMultiLabelClfDataSet(new File(trainFile), DataSetType.ML_CLF_DENSE,
//                    true);
//        }
//
//        return dataSet;
//    }

    static void train(Config config) throws Exception{
        String output = config.getString("output.folder");
        int numIterations = config.getInt("train.numIterations");
        int numLeaves = config.getInt("train.numLeaves");
        double learningRate = config.getDouble("train.learningRate");
        int minDataPerLeaf = config.getInt("train.minDataPerLeaf");
        String modelName = "model";
        double featureSamplingRate = config.getDouble("train.featureSamplingRate");
        double dataSamplingRate = config.getDouble("train.dataSamplingRate");

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        MultiLabelClfDataSet dataSet = loadData(config,config.getString("input.trainData"));

        MultiLabelClfDataSet testSet = null;
        if (config.getBoolean("train.showTestProgress")){
            testSet = loadData(config,config.getString("input.testData"));
        }


        int[] activeFeatures = IntStream.range(0, dataSet.getNumFeatures()).toArray();

        int numClasses = dataSet.getNumClasses();
        System.out.println("number of class = "+numClasses);
        IMLGBConfig imlgbConfig = new IMLGBConfig.Builder(dataSet)
                .dataSamplingRate(dataSamplingRate)
                .featureSamplingRate(featureSamplingRate)
                .learningRate(learningRate)
                .minDataPerLeaf(minDataPerLeaf)
                .numLeaves(numLeaves)
                .numSplitIntervals(config.getInt("train.numSplitIntervals"))
                .usePrior(config.getBoolean("train.usePrior"))
                .build();

        IMLGradientBoosting boosting;
        if (config.getBoolean("train.warmStart")){
            boosting = IMLGradientBoosting.deserialize(new File(output,modelName));
        } else {
            boosting  = new IMLGradientBoosting(numClasses);
        }

        // todo
        boosting.setPredictFashion(IMLGradientBoosting.PredictFashion.CRF);
        System.out.println("During training, the performance is reported using Subset Accuracy optimal predictor");

        IMLGBTrainer trainer = new IMLGBTrainer(imlgbConfig,boosting);

        //todo make it better
        trainer.setActiveFeatures(activeFeatures);

        int progressInterval = config.getInt("train.showProgress.interval");
        for (int i=0;i<numIterations;i++){
            System.out.println("iteration "+i);
            trainer.iterate();
//            System.out.println("model size = "+boosting.getRegressors(0).size());
            if (config.getBoolean("train.showTrainProgress") && (i%progressInterval==0 || i==numIterations-1)){
                MultiLabel[] predictions = boosting.predict(dataSet);
                System.out.println("accuracy on training set = "+ Accuracy.accuracy(dataSet.getMultiLabels(),predictions));
                System.out.println("overlap on training set = "+ Overlap.overlap(boosting, dataSet));
            }
            if (config.getBoolean("train.showTestProgress") && (i%progressInterval==0 || i==numIterations-1)){
                MultiLabel[] predictions = boosting.predict(testSet);
                System.out.println("accuracy on test set = "+ Accuracy.accuracy(testSet.getMultiLabels(),predictions));
                System.out.println("overlap on test set = "+ Overlap.overlap(testSet.getMultiLabels(),predictions));
            }
        }
        File serializedModel =  new File(output,modelName);


        boosting.serialize(serializedModel);
        System.out.println(stopWatch);

    }

    static void tuneForMacroF(Config config) throws Exception{
        String output = config.getString("output.folder");
        String modelName = "model";
        double beta = config.getDouble("tune.FMeasure.beta");
        IMLGradientBoosting boosting = IMLGradientBoosting.deserialize(new File(output,modelName));
        String tuneBy = config.getString("tune.data");
        String dataName;
        switch (tuneBy){
            case "train":
                dataName = config.getString("input.trainData");
                break;
            case "test":
                dataName = config.getString("input.testData");
                break;
            default:
                throw new IllegalArgumentException("tune.data should be train or test");
        }


        MultiLabelClfDataSet dataSet = loadData(config,dataName);
        double[] thresholds = MacroFMeasureTuner.tuneThresholds(boosting,dataSet,beta);
        TunedMarginalClassifier  tunedMarginalClassifier = new TunedMarginalClassifier(boosting,thresholds);
        Serialization.serialize(tunedMarginalClassifier, new File(output,"predictor_macro_f"));
        System.out.println("finish tuning for macro F measure");

    }

    private static void reportForMacroF(Config config, String dataName) throws Exception{
        String output = config.getString("output.folder");
        String modelName = "model";
        File analysisFolder = new File(new File(output,"reports"),dataName+"_reports");
        analysisFolder.mkdirs();
        FileUtils.cleanDirectory(analysisFolder);

        IMLGradientBoosting boosting = IMLGradientBoosting.deserialize(new File(output,modelName));
        TunedMarginalClassifier  tunedMarginalClassifier = (TunedMarginalClassifier)Serialization.deserialize(new File(output, "predictor_macro_f"));

        MultiLabelClfDataSet dataSet = loadData(config,dataName);
        MLMeasures mlMeasures = new MLMeasures(tunedMarginalClassifier,dataSet);
        mlMeasures.getMacroAverage().setLabelTranslator(boosting.getLabelTranslator());

        System.out.println("All measures");
        System.out.println(mlMeasures);
    }

    // todo merge
    private static void reportForInstanceF(Config config, String dataName) throws Exception{
        String output = config.getString("output.folder");
        String modelName = "model";
        File analysisFolder = new File(new File(output,"reports"),dataName+"_reports");
        analysisFolder.mkdirs();
        FileUtils.cleanDirectory(analysisFolder);

        IMLGradientBoosting boosting = IMLGradientBoosting.deserialize(new File(output,modelName));
        MultiLabelClfDataSet dataSet = loadData(config,dataName);
        PlugInF1 plugInF1 = new PlugInF1(boosting);
        MLMeasures mlMeasures = new MLMeasures(plugInF1,dataSet);
        mlMeasures.getMacroAverage().setLabelTranslator(boosting.getLabelTranslator());

        System.out.println("All measures");
        System.out.println(mlMeasures);


        boolean simpleCSV = true;
        if (simpleCSV){
            double probThreshold=config.getDouble("report.classProbThreshold");
            File csv = new File(analysisFolder,"report.csv");
            for (int i=0;i<dataSet.getNumDataPoints();i++){
                String str = PlugInF1Inspector.simplePredictionAnalysis(plugInF1,dataSet,i,probThreshold);
                FileUtils.writeStringToFile(csv,str,true);
            }
        }


        boolean dataInfoToJson = true;
        if (dataInfoToJson){
            Set<String> modelLabels = IntStream.range(0,boosting.getNumClasses()).mapToObj(i->boosting.getLabelTranslator().toExtLabel(i))
                    .collect(Collectors.toSet());

            Set<String> dataSetLabels = DataSetUtil.gatherLabels(dataSet).stream().map(i -> dataSet.getLabelTranslator().toExtLabel(i))
                    .collect(Collectors.toSet());

            JsonGenerator jsonGenerator = new JsonFactory().createGenerator(new File(analysisFolder,"data_info.json"), JsonEncoding.UTF8);
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField("dataSet",dataName);
            jsonGenerator.writeNumberField("numClassesInModel",boosting.getNumClasses());
            jsonGenerator.writeNumberField("numClassesInDataSet",dataSetLabels.size());
            jsonGenerator.writeNumberField("numClassesInModelDataSetCombined",dataSet.getNumClasses());
            Set<String> modelNotDataLabels = SetUtil.complement(modelLabels, dataSetLabels);
            Set<String> dataNotModelLabels = SetUtil.complement(dataSetLabels,modelLabels);
            jsonGenerator.writeNumberField("numClassesInDataSetButNotModel",dataNotModelLabels.size());
            jsonGenerator.writeNumberField("numClassesInModelButNotDataSet",modelNotDataLabels.size());
            jsonGenerator.writeArrayFieldStart("classesInDataSetButNotModel");
            for (String label: dataNotModelLabels){
                jsonGenerator.writeObject(label);
            }
            jsonGenerator.writeEndArray();
            jsonGenerator.writeArrayFieldStart("classesInModelButNotDataSet");
            for (String label: modelNotDataLabels){
                jsonGenerator.writeObject(label);
            }
            jsonGenerator.writeEndArray();
            jsonGenerator.writeNumberField("labelCardinality",dataSet.labelCardinality());

            jsonGenerator.writeEndObject();
            jsonGenerator.close();
        }


        boolean modelConfigToJson = true;
        if (modelConfigToJson){
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writeValue(new File(analysisFolder,"model_config.json"),config);
        }

        boolean dataConfigToJson = true;
        if (dataConfigToJson){
            File dataConfigFile = Paths.get(config.getString("input.folder"),
                    "data_sets",dataName,"data_config.json").toFile();
            if (dataConfigFile.exists()){
                FileUtils.copyFileToDirectory(dataConfigFile,analysisFolder);
            }
        }

        boolean performanceToJson = true;
        if (performanceToJson){
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writeValue(new File(analysisFolder,"performance.json"),mlMeasures);
        }

        boolean individualPerformance = true;
        if (individualPerformance){
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writeValue(new File(analysisFolder,"individual_performance.json"),mlMeasures.getMacroAverage());
        }
    }

    static void report(Config config, String dataName) throws Exception{
        System.out.println("generating reports for data set "+dataName);
        String output = config.getString("output.folder");
        String modelName = "model";
        File analysisFolder = new File(new File(output,"reports"),dataName+"_reports");
        analysisFolder.mkdirs();
        FileUtils.cleanDirectory(analysisFolder);

        IMLGradientBoosting boosting = IMLGradientBoosting.deserialize(new File(output,modelName));
        String predictFashion = config.getString("predict.target").toLowerCase();
        switch (predictFashion){
            case "subsetAccuracy":
                boosting.setPredictFashion(IMLGradientBoosting.PredictFashion.CRF);
                break;
            case "hammingLoss":
                boosting.setPredictFashion(IMLGradientBoosting.PredictFashion.INDEPENDENT);
                break;
            // todo
            case "instanceFMeasure":
                reportForInstanceF(config,dataName);
                return;
            case "macroFMeasure":
                reportForMacroF(config,dataName);
                return;

        }

        MultiLabelClfDataSet dataSet = loadData(config,dataName);

        MLMeasures mlMeasures = new MLMeasures(boosting,dataSet);
        mlMeasures.getMacroAverage().setLabelTranslator(boosting.getLabelTranslator());

        System.out.println("All measures");
        System.out.println(mlMeasures);



        boolean simpleCSV = true;
        if (simpleCSV){
            double probThreshold=config.getDouble("report.classProbThreshold");
            File csv = new File(analysisFolder,"report.csv");
            for (int i=0;i<dataSet.getNumDataPoints();i++){
                String str = IMLGBInspector.simplePredictionAnalysis(boosting,dataSet,i,probThreshold);
                FileUtils.writeStringToFile(csv,str,true);
            }
        }


        boolean topFeaturesToJson = false;
        File distributionFile = new File(new File(config.getString("input.folder"), "meta_data"),"distributions.ser");
        if (distributionFile.exists()){
            topFeaturesToJson = true;
        }
        if (topFeaturesToJson){
            Collection<FeatureDistribution> distributions = (Collection) Serialization.deserialize(distributionFile);
            int limit = config.getInt("report.topFeatures.limit");
            List<TopFeatures> topFeaturesList = IntStream.range(0,boosting.getNumClasses())
                    .mapToObj(k -> IMLGBInspector.topFeatures(boosting, k, limit, distributions))
                    .collect(Collectors.toList());
            ObjectMapper mapper = new ObjectMapper();
            String file = "top_features.json";
            mapper.writeValue(new File(analysisFolder,file), topFeaturesList);
        }


        boolean rulesToJson = true;
        if (rulesToJson){
            int ruleLimit = config.getInt("report.rule.limit");
            int numDocsPerFile = config.getInt("report.numDocsPerFile");
            int numFiles = (int)Math.ceil((double)dataSet.getNumDataPoints()/numDocsPerFile);

            double probThreshold=config.getDouble("report.classProbThreshold");
            int labelSetLimit = config.getInt("report.labelSetLimit");

            for (int i=0;i<numFiles;i++){
                int start = i*numDocsPerFile;
                int end = start+numDocsPerFile;
                List<MultiLabelPredictionAnalysis> partition = new ArrayList<>();
                for (int a=start;a<end && a<dataSet.getNumDataPoints();a++){
                    List<Integer> classes = new ArrayList<Integer>();
                    for (int k = 0; k < boosting.getNumClasses(); k++){
                        if (boosting.predictClassProb(dataSet.getRow(a),k)>=probThreshold){
                            classes.add(k);
                        }
                    }
                    partition.add(IMLGBInspector.analyzePrediction(boosting, dataSet, a, classes, ruleLimit,labelSetLimit));
                }
                ObjectMapper mapper = new ObjectMapper();

                String file = "report_"+(i+1)+".json";
                mapper.writeValue(new File(analysisFolder,file), partition);
            }
        }


        boolean dataInfoToJson = true;
        if (dataInfoToJson){
            Set<String> modelLabels = IntStream.range(0,boosting.getNumClasses()).mapToObj(i->boosting.getLabelTranslator().toExtLabel(i))
                    .collect(Collectors.toSet());

            Set<String> dataSetLabels = DataSetUtil.gatherLabels(dataSet).stream().map(i -> dataSet.getLabelTranslator().toExtLabel(i))
                    .collect(Collectors.toSet());

            JsonGenerator jsonGenerator = new JsonFactory().createGenerator(new File(analysisFolder,"data_info.json"), JsonEncoding.UTF8);
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField("dataSet",dataName);
            jsonGenerator.writeNumberField("numClassesInModel",boosting.getNumClasses());
            jsonGenerator.writeNumberField("numClassesInDataSet",dataSetLabels.size());
            jsonGenerator.writeNumberField("numClassesInModelDataSetCombined",dataSet.getNumClasses());
            Set<String> modelNotDataLabels = SetUtil.complement(modelLabels, dataSetLabels);
            Set<String> dataNotModelLabels = SetUtil.complement(dataSetLabels,modelLabels);
            jsonGenerator.writeNumberField("numClassesInDataSetButNotModel",dataNotModelLabels.size());
            jsonGenerator.writeNumberField("numClassesInModelButNotDataSet",modelNotDataLabels.size());
            jsonGenerator.writeArrayFieldStart("classesInDataSetButNotModel");
            for (String label: dataNotModelLabels){
                jsonGenerator.writeObject(label);
            }
            jsonGenerator.writeEndArray();
            jsonGenerator.writeArrayFieldStart("classesInModelButNotDataSet");
            for (String label: modelNotDataLabels){
                jsonGenerator.writeObject(label);
            }
            jsonGenerator.writeEndArray();
            jsonGenerator.writeNumberField("labelCardinality",dataSet.labelCardinality());

            jsonGenerator.writeEndObject();
            jsonGenerator.close();
        }


        boolean modelConfigToJson = true;
        if (modelConfigToJson){
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writeValue(new File(analysisFolder,"model_config.json"),config);
        }

        boolean dataConfigToJson = true;
        if (dataConfigToJson){
            File dataConfigFile = Paths.get(config.getString("input.folder"),
                    "data_sets",dataName,"data_config.json").toFile();
            if (dataConfigFile.exists()){
                FileUtils.copyFileToDirectory(dataConfigFile,analysisFolder);
            }
        }

        boolean performanceToJson = true;
        if (performanceToJson){
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writeValue(new File(analysisFolder,"performance.json"),mlMeasures);
        }

        boolean individualPerformance = true;
        if (individualPerformance){
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writeValue(new File(analysisFolder,"individual_performance.json"),mlMeasures.getMacroAverage());
        }

        System.out.println("reports generated");
    }






}
