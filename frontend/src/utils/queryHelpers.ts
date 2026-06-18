export const buildEffectiveQuery = (
  baseTable: string,
  mapping: any,
  side: 'source' | 'target'
): string => {
  const isManual = side === 'source' ? mapping.isManualQuerySource : mapping.isManualQueryTarget;
  let baseQuery = '';

  if (isManual) {
    let manual = side === 'source' ? mapping.customQuerySource : mapping.customQueryTarget;
    if (!manual?.trim()) return '';
    baseQuery = manual.trim();
  } else {
    if (!baseTable?.trim()) return '';
    baseQuery = `SELECT * FROM ${baseTable}`;
  }

  const conditions: string[] = [];
  const dateColumn = mapping.dateColumn?.trim() || '';
  const startDate  = mapping.startDate  || '';
  const endDate    = mapping.endDate    || '';

  if (dateColumn) {
    if (startDate) conditions.push(`${dateColumn} >= '${startDate}'`);
    if (endDate)   conditions.push(`${dateColumn} <= '${endDate}'`);
  }

  const extraWhere = (side === 'source' ? mapping.extraWhereSource : mapping.extraWhereTarget)?.trim();
  if (extraWhere) conditions.push(`(${extraWhere})`);

  const limitClause = mapping.rowLimit ? ` LIMIT ${mapping.rowLimit}` : '';

  if (conditions.length === 0 && !limitClause) {
    return baseQuery;
  }

  const whereClause = conditions.length ? ` WHERE ${conditions.join(' AND ')}` : '';
  
  if (isManual) {
    // Wrap manual queries safely as a subquery so we can append WHERE and LIMIT
    // without parsing complex SQL like JOINs or GROUP BYs.
    return `SELECT * FROM (\n${baseQuery}\n) AS custom_query${whereClause}${limitClause}`;
  } else {
    return `${baseQuery}${whereClause}${limitClause}`;
  }
};

export const detectPrimaryKeysFromQuery = (query: string): string[] => {
  if (!query) return [];
  const upperQuery = query.toUpperCase();
  const orderByIndex = upperQuery.lastIndexOf('ORDER BY');
  if (orderByIndex !== -1) {
    let orderClause = query.substring(orderByIndex + 8).trim();
    const limitIndex = orderClause.toUpperCase().indexOf('LIMIT');
    if (limitIndex !== -1) {
      orderClause = orderClause.substring(0, limitIndex).trim();
    }
    if (orderClause.endsWith(';')) {
      orderClause = orderClause.substring(0, orderClause.length - 1).trim();
    }
    
    const extracted = orderClause.split(',').map(part => {
       let col = part.trim().split(/\s+/)[0]; 
       if (col.includes('.')) col = col.split('.').pop() || col; 
       return col.replace(/['"`\[\]]/g, ''); 
    }).filter(Boolean);
    return extracted;
  }
  return [];
};
