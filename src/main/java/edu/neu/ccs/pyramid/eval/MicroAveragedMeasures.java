package edu.neu.ccs.pyramid.eval;

/**
 * Created by chengli on 10/3/14.
 */
public class MicroAveragedMeasures {
    private int positive;
    private int negative;
    private int truePositive;
    private int trueNegative;
    private int falsePositive;
    private int falseNegative;
    private double truePositiveRate;
    private double trueNegativeRate;
    private double falsePositiveRate;
    private double falseNegativeRate;
    private double accuracy;
    private double precision;
    private double recall;
    private double f1;

    public MicroAveragedMeasures(ConfusionMatrix confusionMatrix){
        int numClass = confusionMatrix.getNumClasses();
        for (int k=0;k<numClass;k++){
            PerClassMeasures measures = new PerClassMeasures(confusionMatrix,k);
            truePositive += measures.getTruePositive();
            trueNegative += measures.getTrueNegative();
            falsePositive += measures.getFalsePositive();
            falseNegative += measures.getFalseNegative();
        }
        positive = truePositive + falseNegative;
        negative = trueNegative + falsePositive;
        truePositiveRate = ((double)truePositive)/positive;
        trueNegativeRate = ((double)trueNegative)/negative;
        falsePositiveRate = ((double)falsePositive)/negative;
        falseNegativeRate = ((double)falseNegative)/positive;
        accuracy = ((double)(truePositive+trueNegative))/(positive+negative);
        precision = ((double)truePositive)/(truePositive+falsePositive);
        recall = truePositiveRate;
        f1 = FMeasure.f1(precision,recall);
    }



    public double getPrecision() {
        return precision;
    }

    public double getRecall() {
        return recall;
    }

    public double getF1() {
        return f1;
    }

    @Override
    public String toString() {
        return "MicroAveragedMeasures{" +
                "precision=" + precision +
                ", recall=" + recall +
                ", f1=" + f1 +
                '}';
    }
}
