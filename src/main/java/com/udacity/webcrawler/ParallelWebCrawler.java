package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;


import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final ForkJoinPool pool;
  private final List<Pattern> ignoredUrls;
  private final PageParserFactory parserFactory;
  private final int maxDepth;

  @Inject
  ParallelWebCrawler(
                  Clock clock,
                  PageParserFactory parserFactory,
                  @MaxDepth int maxDepth,
                  @Timeout Duration timeout,
                  @PopularWordCount int popularWordCount,
                  @IgnoredUrls List<Pattern> ignoredUrls,
                  @TargetParallelism int threadCount) {
    this.maxDepth = maxDepth;
    this.clock = clock;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.ignoredUrls = ignoredUrls;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.parserFactory = parserFactory;
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
    ConcurrentSkipListSet<String> visitedUrls = new ConcurrentSkipListSet<>();
    for (String url : startingUrls) {
      pool.invoke(new InternalCrawler(url, deadline, maxDepth, counts,
                      visitedUrls));
    }

    if (counts.isEmpty()) {
      return new CrawlResult.Builder()
                      .setWordCounts(counts)
                      .setUrlsVisited(visitedUrls.size())
                      .build();
    }
    return new CrawlResult.Builder()
                    .setWordCounts(WordCounts.sort(counts,popularWordCount))
                                    .setUrlsVisited(visitedUrls.size())
                                    .build();
  }

  public class InternalCrawler extends RecursiveAction {
    private String url;
    private Instant deadline;
    private int maxDepth;
    private ConcurrentHashMap<String, Integer> counts;
    private ConcurrentSkipListSet<String> visitedUrls;



    public InternalCrawler(String url,Instant deadline,int maxDepth,
                           ConcurrentHashMap<String,Integer> counts,ConcurrentSkipListSet<String> visitedUrls) {
      this.url = url;
      this.deadline = deadline;
      this.maxDepth = maxDepth;
      this.counts = counts;
      this.visitedUrls = visitedUrls;
    }
    @Override
    protected void compute() {
      if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
        return;
      }
      for (Pattern pattern : ignoredUrls) {
        if (pattern.matcher(url).matches()) {
          return;
        }
      }
      if (!visitedUrls.add(url)) {
        return;
      }
      PageParser.Result result = parserFactory.get(url).parse();

      for (ConcurrentHashMap.Entry<String, Integer> e :
                      result.getWordCounts().entrySet()) {
        if (counts.containsKey(e.getKey())) {
          counts.put(e.getKey(), e.getValue() + counts.get(e.getKey()));
        } else {
          counts.put(e.getKey(), e.getValue());
        }
      }
      List<InternalCrawler> subtasks = new ArrayList<>();
      for (String link : result.getLinks()) {
        subtasks.add(new InternalCrawler(link, deadline, maxDepth - 1, counts,
                        visitedUrls));
      }
      invokeAll(subtasks);
    }
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }
}
