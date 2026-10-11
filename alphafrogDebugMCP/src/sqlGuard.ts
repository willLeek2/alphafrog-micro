/**
 * remote_pg_query 的只读 SQL 校验。
 * 业务表必须以 alphafrog_ 开头；另外放行复制状态视图 pg_stat_replication。
 */

const ALLOWED_TABLE_PREFIX = "alphafrog_";

/** 额外放行的只读系统视图，按小写比较，不含 schema。 */
const ALLOWED_READONLY_RELATIONS = new Set(["pg_stat_replication"]);

const DANGEROUS_KEYWORDS =
  /\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|GRANT|REVOKE|EXEC|EXECUTE|COPY|VACUUM|MERGE)\b/i;

const IDENT = String.raw`(?:"[^"]+"|[a-zA-Z_][a-zA-Z0-9_]*)`;
const TABLE_REF_RE = new RegExp(
  String.raw`\b(?:FROM|JOIN)\s+(${IDENT})(?:\s*\.\s*(${IDENT}))?`,
  "gi"
);

function unquoteIdent(raw: string): string {
  const trimmed = raw.trim();
  if (trimmed.startsWith('"') && trimmed.endsWith('"') && trimmed.length >= 2) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

/** FROM/JOIN 后面的关系名。带 schema 时取最后一段。 */
export function relationBaseName(match: RegExpMatchArray): string {
  return unquoteIdent(match[2] ?? match[1]);
}

export function isAllowedRelation(name: string): boolean {
  const normalized = name.toLowerCase();
  return normalized.startsWith(ALLOWED_TABLE_PREFIX) || ALLOWED_READONLY_RELATIONS.has(normalized);
}

/** 通过返回 null；拒绝返回给调用方的说明。 */
export function validateSql(sql: string): string | null {
  const stripped = sql.trim();
  if (!stripped.toUpperCase().startsWith("SELECT")) {
    return "Only SELECT statements are allowed";
  }
  if (DANGEROUS_KEYWORDS.test(stripped)) {
    return "Dangerous keyword detected";
  }
  for (const match of stripped.matchAll(TABLE_REF_RE)) {
    const name = relationBaseName(match);
    if (!isAllowedRelation(name)) {
      return `Table '${name}' is not allowed (must start with 'alphafrog_' or be pg_stat_replication)`;
    }
  }
  return null;
}
