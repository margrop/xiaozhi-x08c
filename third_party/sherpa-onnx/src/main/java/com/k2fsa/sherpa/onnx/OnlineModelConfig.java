package com.k2fsa.sherpa.onnx;

public class OnlineModelConfig {
    public OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
    public OnlineParaformerModelConfig paraformer = new OnlineParaformerModelConfig();
    public OnlineZipformer2CtcModelConfig zipformer2Ctc = new OnlineZipformer2CtcModelConfig();
    public OnlineNeMoCtcModelConfig neMoCtc = new OnlineNeMoCtcModelConfig();
    public OnlineToneCtcModelConfig toneCtc = new OnlineToneCtcModelConfig();
    public String tokens = "";
    public int numThreads = 1;
    public boolean debug = false;
    public String provider = "cpu";
    public String modelType = "";
    public String modelingUnit = "";
    public String bpeVocab = "";
}

class OnlineParaformerModelConfig {
    public String encoder = "";
    public String decoder = "";
}

class OnlineZipformer2CtcModelConfig {
    public String model = "";
}

class OnlineNeMoCtcModelConfig {
    public String model = "";
}

class OnlineToneCtcModelConfig {
    public String model = "";
}
