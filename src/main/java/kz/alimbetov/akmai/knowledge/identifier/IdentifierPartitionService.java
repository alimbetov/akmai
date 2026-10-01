package kz.alimbetov.akmai.knowledge.identifier;

import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IdentifierPartitionService {

    private final JdbcTemplate jdbcTemplate;

    public IdentifierPartitionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureMonth(YearMonth month) {
        String suffix = "%04d_%02d".formatted(month.getYear(), month.getMonthValue());
        String table = "document_identifier_" + suffix;
        var from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        var to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        String sql = """
                CREATE TABLE IF NOT EXISTS %s
                PARTITION OF document_identifier
                FOR VALUES FROM ('%s') TO ('%s')
                """.formatted(table, from, to);

        jdbcTemplate.execute(sql);
    }
}
