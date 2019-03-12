package org.apache.gobblin.compaction.mapreduce;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.lib.input.CombineFileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.CombineFileSplit;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.util.VersionInfo;


public abstract class CompactionCombineFileInputFormat<KI, KO> extends CombineFileInputFormat<KI, KO> {
  private static final String COMPACTION_JOB_PREFIX = "compaction.job.";

  /**
   * Properties related to the input format of the compaction job of a dataset.
   */
  private static final String COMPACTION_JOB_MAPRED_MAX_SPLIT_SIZE = COMPACTION_JOB_PREFIX + "mapred.max.split.size";
  private static final long DEFAULT_COMPACTION_JOB_MAPRED_MAX_SPLIT_SIZE = 268435456;
  private static final String COMPACTION_JOB_MAPRED_MIN_SPLIT_SIZE = COMPACTION_JOB_PREFIX + "mapred.min.split.size";
  private static final long DEFAULT_COMPACTION_JOB_MAPRED_MIN_SPLIT_SIZE = 268435456;

  private static final int SPLIT_MAX_NUM_LOCATIONS = 10;

  @Override
  public List<InputSplit> getSplits(JobContext cx) throws IOException {
    Job modifiedJob = Job.getInstance(cx.getConfiguration());
    setSplitSize(modifiedJob);
    FileInputFormat.setInputDirRecursive(modifiedJob, true);
    return cleanSplits(super.getSplits(modifiedJob));
  }

  private void setSplitSize(JobContext cx) {
    super.setMaxSplitSize(cx.getConfiguration().getLong(COMPACTION_JOB_MAPRED_MAX_SPLIT_SIZE,
        DEFAULT_COMPACTION_JOB_MAPRED_MAX_SPLIT_SIZE));
    super.setMinSplitSizeNode(cx.getConfiguration().getLong(COMPACTION_JOB_MAPRED_MIN_SPLIT_SIZE,
        DEFAULT_COMPACTION_JOB_MAPRED_MIN_SPLIT_SIZE));
  }

  /**
   * Set the number of locations in the split to SPLIT_MAX_NUM_LOCATIONS if it is larger than
   * SPLIT_MAX_NUM_LOCATIONS (MAPREDUCE-5186).
   */
  private static List<InputSplit> cleanSplits(List<InputSplit> splits) throws IOException {
    if (VersionInfo.getVersion().compareTo("2.3.0") >= 0) {
      // This issue was fixed in 2.3.0, if newer version, no need to clean up splits
      return splits;
    }

    List<InputSplit> cleanedSplits = Lists.newArrayList();

    for (int i = 0; i < splits.size(); i++) {
      CombineFileSplit oldSplit = (CombineFileSplit) splits.get(i);
      String[] locations = oldSplit.getLocations();

      Preconditions.checkNotNull(locations, "CombineFileSplit.getLocations() returned null");

      if (locations.length > SPLIT_MAX_NUM_LOCATIONS) {
        locations = Arrays.copyOf(locations, SPLIT_MAX_NUM_LOCATIONS);
      }

      cleanedSplits.add(new CombineFileSplit(oldSplit.getPaths(), oldSplit.getStartOffsets(), oldSplit.getLengths(),
          locations));
    }
    return cleanedSplits;
  }
}
