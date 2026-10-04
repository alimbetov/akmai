package kz.alimbetov.akmai.runtimeconfig;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAppParameterRepository
        implements AppParameterRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresAppParameterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AppParameter> find(String key) {
        List<AppParameter> values = jdbcTemplate.query(
                """
                SELECT parameter_key,
                       parameter_type,
                       parameter_value,
                       row_version,
                       updated_at,
                       updated_by
                FROM app_parameter
                WHERE parameter_key = ?
                """,
                (rs, rowNum) -> map(rs),
                key
        );
        return values.stream().findFirst();
    }

    @Override
    public Optional<AppParameter> updateBoolean(
            String key,
            boolean value,
            long expectedVersion,
            String updatedBy
    ) {
        List<AppParameter> updated = jdbcTemplate.query(
                """
                UPDATE app_parameter
                SET parameter_value = ?,
                    row_version = row_version + 1,
                    updated_at = clock_timestamp(),
                    updated_by = ?
                WHERE parameter_key = ?
                  AND parameter_type = 'BOOLEAN'
                  AND row_version = ?
                RETURNING parameter_key,
                          parameter_type,
                          parameter_value,
                          row_version,
                          updated_at,
                          updated_by
                """,
                ps -> {
                    ps.setString(1, Boolean.toString(value));
                    ps.setString(2, updatedBy);
                    ps.setString(3, key);
                    ps.setLong(4, expectedVersion);
                },
                (rs, rowNum) -> map(rs)
        );
        return updated.stream().findFirst();
    }

    private AppParameter map(ResultSet rs) throws SQLException {
        return new AppParameter(
                rs.getString("parameter_key"),
                AppParameterType.valueOf(
                        rs.getString("parameter_type")
                ),
                rs.getString("parameter_value"),
                rs.getLong("row_version"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("updated_by")
        );
    }
}
