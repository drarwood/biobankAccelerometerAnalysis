import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.text.SimpleDateFormat;

/**
 * Calculates epoch summaries from an AX3 .CWA file.
 * Class/application can be called from the command line as follows:
 * java AxivityAx3Epochs inputFile.CWA 
 */
public class AxivityAx3Epochs
{

    /**
     * Parse command line args, then call method to identify & write epochs.
     */
    public static void main(String[] args) {
        //variables to store default parameter options
        String accFile = "";
        String[] functionParameters = new String[0];
        String outputFile = "";
        int epochPeriod = 60;
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S");
        BandpassFilter filter = new BandpassFilter(0.50, 15, 100);
        Boolean startEpochWholeMinute = false;
        Boolean startEpochWholeSecond = false;
        Boolean interpolateSample = true;
        if (args.length < 1) {
            String invalidInputMsg = "Invalid input, ";
            invalidInputMsg += "please enter at least 1 parameter, e.g.\n";
            invalidInputMsg += "java AxivityAx3Epochs inputFile.CWA";
            System.out.println(invalidInputMsg);
            System.exit(0);
        } else if (args.length == 1) {
            //singe parameter needs to be accFile
            accFile = args[0]; 
            outputFile = accFile.split("\\.")[0] + "Epoch.csv";
        } else {
            //load accFile, and also copy functionParameters (args[1:])
            accFile = args[0];
            outputFile = accFile.split("\\.")[0] + "Epoch.csv";
            functionParameters = Arrays.copyOfRange(args, 1, args.length);

            //update default values by looping through available user parameters
            for (String individualParam : functionParameters) {
                //individual_Parameters will look like "epoch_period:60"
                String funcName = individualParam.split(":")[0];
                String funcParam = individualParam.split(":")[1];
                if (funcName.equals("outputFile")) {
                    outputFile = funcParam;
                } else if (funcName.equals("epochPeriod")) {
                    epochPeriod = Integer.parseInt(funcParam);
                } else if (funcName.equals("timeFormat")) {
                    timeFormat = new SimpleDateFormat(funcParam);
                } else if (funcName.equals("filter")) {
                    if (!Boolean.parseBoolean(funcParam.toLowerCase())) {
                            filter = null; //i.e. we don't want default filter
                        }
                } else if (funcName.equals("startEpochWholeMinute")) {
                    startEpochWholeMinute = Boolean.parseBoolean(
                            funcParam.toLowerCase());
                } else if (funcName.equals("startEpochWholeSecond")) {
                    startEpochWholeSecond = Boolean.parseBoolean(
                            funcParam.toLowerCase());
                } else if (funcName.equals("interpolateSample")) {
                    interpolateSample = Boolean.parseBoolean(
                            funcParam.toLowerCase());
                }
            }
        }    

        //process file if input parameters are all ok
        writeCwaEpochs(accFile, outputFile, epochPeriod, timeFormat,
                startEpochWholeMinute, startEpochWholeSecond, interpolateSample,
                filter);   
    }

    /**
     * Read CWA file blocks, then call method to write epochs from raw data.
     * Epochs will be written to path "outputFile".
     */
    private static void writeCwaEpochs(
            String accFile,
            String outputFile,
            int epochPeriod,
            SimpleDateFormat timeFormat,
            Boolean startEpochWholeMinute,
            Boolean startEpochWholeSecond,
            Boolean interpolateSample,
            BandpassFilter filter) { 
        //file read/write objects
        FileChannel rawAccReader = null;
        BufferedWriter epochFileWriter = null;
        ByteBuffer buf = ByteBuffer.allocate(512);      
        try {
            rawAccReader = new FileInputStream(accFile).getChannel();            
            epochFileWriter = new BufferedWriter(new FileWriter(outputFile));
            
            //data block support variables
            String header = "";        
            //epoch creation support variables
            Calendar epochStartTime = null;//new GregorianCalendar();    
            List<Date> epochDatetimeArray = new ArrayList<Date>();
            List<Double> epochSvmVals = new ArrayList<Double>();
            List<Double> xVals = new ArrayList<Double>();
            List<Double> yVals = new ArrayList<Double>();
            List<Double> zVals = new ArrayList<Double>();
            String epochSummary = "";
            String epochHeader = "Timestamp,SVM,Xmean,Ymean,Zmean,Xrange,";
            epochHeader += "Yrange,Zrange,Xstd,Ystd,Zstd,Temp,Samples"; 

            //now read every page in CWA file
            while(rawAccReader.read(buf) != -1) {
                buf.flip();
                buf.order(ByteOrder.LITTLE_ENDIAN);
                header = (char)buf.get() + "";
                header += (char)buf.get() + "";
                if(header.equals("MD")) {
                    //Read first page (& data-block) to get time, temp,
                    //measureFreq & start-epoch values
                    //epochStartTime = parseHeader(buf,epochFileWriter);
                    writeLine(epochFileWriter, epochHeader);
                } else if(header.equals("AX")) {
                    //read each individual page block, and process epochs...
                    epochStartTime = processDataBlockIdentifyEpochs(buf,
                            epochFileWriter, timeFormat, epochStartTime,
                            epochPeriod, xVals, yVals, zVals, epochSvmVals,
                            interpolateSample, filter);                        
                }
                buf.clear();
                //option to provide status update to user...
                //page_count++;
                //if(page_count % 1000 == 0)
                    //System.out.print((page_count*100/memorySizePages) + "%\b\b\b");
            }   
            rawAccReader.close();
            epochFileWriter.close();
        } catch (Exception excep) {
            String errorMessage = "error reading/writing file " + outputFile;
            errorMessage += ": " + excep.toString();
            System.out.println(errorMessage);
            System.exit(0);
        }
    }

    /**
     * Read data block HEX values, store each raw reading, then continually test
     * if an epoch of data has been collected or not. Finally, write each epoch
     * to <epochFileWriter>. Method also updates and returns <epochStartTime>.
     * CWA format is described at:
     * https://code.google.com/p/openmovement/source/browse/downloads/AX3/AX3-CWA-Format.txt
     */
    private static Calendar processDataBlockIdentifyEpochs(
            ByteBuffer buf,
            BufferedWriter epochFileWriter,
            SimpleDateFormat timeFormat,
            Calendar epochStartTime,
            int epochPeriod,
            List<Double> xVals,
            List<Double> yVals,
            List<Double> zVals,
            List<Double> epochSvmVals,
            Boolean interpolateSample,
            BandpassFilter filter) {
        //read block header items
        long blockTimestamp = getUnsignedInt(buf,14);// buf.getInt(14);
        int light = getUnsignedShort(buf,18);// buf.getShort(18);      
        int temperature = getUnsignedShort(buf,20);// buf.getShort(20);
        byte rateCode = buf.get(24);
        byte numAxesBPS = buf.get(25);
        short timestampOffset = buf.getShort(26);
        int sampleCount = getUnsignedShort(buf, 28);// buf.getShort(28);
        //determine sample frequency        
        double sampleFreq = 3200 / (1 << (15 - (rateCode & 15)));
        if (sampleFreq <= 0) {
            sampleFreq = 1;
        }
		double readingGapMs = 1000.0 / sampleFreq;
        //calculate num bytes per sample...
        byte bytesPerSample = 4;
        int NUM_AXES_PER_SAMPLE = 3;
        if ((numAxesBPS & 0x0f) == 2) {
            bytesPerSample = 6; // 3*16-bit
        } else if ((numAxesBPS & 0x0f) == 0) {
            bytesPerSample = 4; // 3*10-bit + 2
        }
        //determine block start time
        Calendar blockTime = getCwaTimestamp((int)blockTimestamp);        
        float offsetStart = (float)-timestampOffset / (float)sampleFreq;        
        blockTime.add(Calendar.MILLISECOND, (int)(offsetStart*1000));
        
        
        //set target epoch start time of very first block
        if(epochStartTime==null) {
            epochStartTime=getCwaTimestamp((int)blockTimestamp);
            epochStartTime.add(Calendar.MILLISECOND, (int)(offsetStart*1000));
        }

        //epoch variables
        String epochSummary = "";
        double sumSvm = 0;
        double xMean = 0;
        double yMean = 0;
        double zMean = 0;
        double xRange = 0;
        double yRange = 0;
        double zRange = 0;
        double xStd = 0;
        double yStd = 0;
        double zStd = 0;     

        //raw reading values
        long value = 0; // x/y/z vals
        short xRaw = 0;
        short yRaw = 0;
        short zRaw = 0;
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        
        //loop through each line in data block & check if it is last in epoch
        //then write epoch summary to file
        //an epoch will have a start+end time, and be of fixed duration            
        int currentPeriod;
        for (int i = 0; i<sampleCount; i++) {
            if (bytesPerSample == 4) {
                value = getUnsignedInt(buf, 30 +4*i);
                // Sign-extend 10-bit values, adjust for exponents
                xRaw = (short)((short)(0xffffffc0 & (value <<  6)) >> (6 - ((byte)(value >> 30))));
                yRaw = (short)((short)(0xffffffc0 & (value >>  4)) >> (6 - ((byte)(value >> 30))));
                zRaw = (short)((short)(0xffffffc0 & (value >> 14)) >> (6 - ((byte)(value >> 30))));
            } else if (bytesPerSample == 6) {
                xRaw = buf.getShort(30 + 2 * NUM_AXES_PER_SAMPLE * i + 0);
                yRaw = buf.getShort(30 + 2 * NUM_AXES_PER_SAMPLE * i + 2);
                zRaw = buf.getShort(30 + 2 * NUM_AXES_PER_SAMPLE * i + 4);                          
            } else {
                xRaw = 0;
                yRaw = 0;
                zRaw = 0;
            }            
            x = xRaw / 256.0;
            y = yRaw / 256.0;
            z = zRaw / 256.0;

            //check we have collected enough values to form an epoch
            //todo would I be better simply calculating an epoch end-time here?
            //Rather than constantly calculating page_time - epochStartTime ???
            currentPeriod = (int) ((blockTime.getTimeInMillis() -
                    epochStartTime.getTimeInMillis())/1000);
            if (currentPeriod >= epochPeriod) { 
                //band-pass filter SVM-1 values
                if (filter != null) {
                    filter.filter(epochSvmVals);
                }

                //now take abs(SVM-1) vals which must be done after filtering
                abs(epochSvmVals);

                //calculate epoch summary values
                sumSvm = sum(epochSvmVals);
                xMean = mean(xVals);
                yMean = mean(yVals);
                zMean = mean(zVals);
                xRange = range(xVals);
                yRange = range(yVals);
                zRange = range(zVals);
                xStd = std(xVals, xMean);
                yStd = std(yVals, yMean);
                zStd = std(zVals, zMean);

                //sometimes more/less samples can be recorded than expected
                if (interpolateSample) {
                    sumSvm = sumSvm * ((double)xVals.size() / epochPeriod);
                    sumSvm = sumSvm / sampleFreq;
                }

                //write summary values to file
                epochSummary = timeFormat.format(epochStartTime.getTime());
                epochSummary += "," + sumSvm;
                epochSummary += "," + xMean + "," + yMean + "," + zMean;
                epochSummary += "," + xRange + "," + yRange + "," + zRange;
                epochSummary += "," + xStd + "," + yStd + "," + zStd;
                epochSummary += "," + temperature + "," + xVals.size();
                writeLine(epochFileWriter, epochSummary); 
                       
                //reset target start time and reset arrays for next epoch
                epochStartTime.add(Calendar.SECOND, epochPeriod);
                xVals.clear();
                yVals.clear();
                zVals.clear();
                epochSvmVals.clear();
            }
            //store axes and vector magnitude values for every reading
            xVals.add(x);
            yVals.add(y);
            zVals.add(z);
            epochSvmVals.add(getVectorMagnitude(x,y,z));
            //System.out.println(timeFormat.format(blockTime.getTime()) + "," + x + "," + y + "," + z);
            blockTime.add(Calendar.MILLISECOND, (int)readingGapMs);            
        }
        return epochStartTime;
    }

    /**
     * Prase header HEX values and return ??
     * CWA format is described at:
     * https://code.google.com/p/openmovement/source/browse/downloads/AX3/AX3-CWA-Format.txt
     */
    private static Calendar parseHeader(
            ByteBuffer buf,
            BufferedWriter epochWriter) {
        //todo ideally return estimate of file size...        
        //deviceId = getUnsignedShort(buf,4);// buf.getShort(4);
        //sessionId = getUnsignedInt(buf,6);// buf.getInt(6); 
        //sequenceId = getUnsignedInt(buf,10);// buf.getInt(10);                 
        long startTimestamp = getUnsignedInt(buf,13);// buf.getInt(14);
        System.out.println(startTimestamp);
        return getCwaTimestamp((int)startTimestamp);
        //return memorySizePages;
    }

    //credit for next 2 methods goes to:
    //http://stackoverflow.com/questions/9883472/is-it-possiable-to-have-an-unsigned-bytebuffer-in-java
    private static long getUnsignedInt(ByteBuffer bb, int position) {
        return ((long) bb.getInt(position) & 0xffffffffL);
    }

    private static int getUnsignedShort(ByteBuffer bb, int position) {
        return (bb.getShort(position) & 0xffff);
    }

    private static Calendar getCwaTimestamp(int cwaTimestamp) {
        Calendar tStamp = new GregorianCalendar();
        int year = (int)((cwaTimestamp >> 26) & 0x3f) + 2000;
        int month = (int)((cwaTimestamp >> 22) & 0x0f);
        int day = (int)((cwaTimestamp >> 17) & 0x1f);
        int hours = (int)((cwaTimestamp >> 12) & 0x1f);
        int mins = (int)((cwaTimestamp >>  6) & 0x3f);
        int secs = (int)((cwaTimestamp      ) & 0x3f);
        tStamp.setTimeInMillis(0); //Otherwise milliseconds is undefined(!)
        tStamp.set(year, month - 1, day, hours, mins, secs); //Month has 0-index
        return tStamp;
    }            
      
    private static double getVectorMagnitude(double x, double y, double z) {
        //return Math.abs(Math.sqrt(x*x + y*y + z*z)-1);
        return Math.sqrt(x*x + y*y + z*z)-1;
    }

    private static void abs(List<Double> vals) {
        for(int c=0; c<vals.size(); c++) {
            vals.set(c, Math.abs(vals.get(c)));
        }
    }

    private static double sum(List<Double> vals) {
        if(vals.size()==0) {
            return Double.NaN;
        }
        double sum = 0;
        for(int c=0; c<vals.size(); c++) {
            sum += vals.get(c);
        }
        return sum;
    }
    
    private static double mean(List<Double> vals) {
        if(vals.size()==0) {
            return Double.NaN;
        }
        return sum(vals) / (double)vals.size();
    }
    	
    private static double range(List<Double> vals) {
        if(vals.size()==0) {
            return Double.NaN;
        }
        double min = Double.MIN_VALUE;
        double max = Double.MAX_VALUE;
        for(int c=0; c<vals.size(); c++) {
            if (vals.get(c) < min) {
                min = vals.get(c);
            } else if (vals.get(c) > max) {
                max = vals.get(c);
            }
        }
        return max - min;
    }    	

    private static double std(List<Double> vals, double mean) {
        if(vals.size()==0) {
            return Double.NaN;
        }
        double var = 0; //variance
        double len = vals.size()*1.0; //length
        for(int c=0; c<vals.size(); c++) {
            var += ((vals.get(c) - mean) * (vals.get(c) - mean)) / len;
        }
        return Math.sqrt(var);
    }

    private static void writeLine(BufferedWriter fileWriter, String line) {
        try {
            fileWriter.write(line + "\n");
        } catch (Exception excep) {
            System.out.println(excep.toString());
        }
    }
      
}