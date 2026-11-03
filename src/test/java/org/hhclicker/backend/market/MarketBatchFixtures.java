package org.hhclicker.backend.market;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.hhclicker.backend.market.MarketAnalysisRepository.*;

final class MarketBatchFixtures {
    static Run run(UUID resume, int count) {
        var sources = IntStream.range(0, count).mapToObj(index -> new Source(UUID.randomUUID(), "" + index,
                "Java", "id:" + index, "Company", "Java Spring", Instant.now())).toList();
        return new Run(UUID.randomUUID(), resume, "Java", "", Instant.now(), "RUNNING", "EXTRACT", 0,
                null, sources, List.of(), List.of(), List.of(), null, null, null, 0);
    }
    static MarketExtractionResults.Accepted facts(Run run, int start, int size) {
        return new MarketExtractionResults.Accepted(run.sources().subList(start, start + size).stream()
                .map(source -> new Fact(source.id(), "Java", "TECHNOLOGY", "MENTIONED", "Java")).toList(), List.of());
    }
}
