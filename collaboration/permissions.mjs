export const ROLES = Object.freeze(['admin', 'scorer', 'member']);
const readActions = new Set(['readTeam', 'readSchedule', 'readGame', 'readStatistics', 'exportReport']);
const managementActions = new Set(['editTeam', 'linkPlayers', 'archivePlayer', 'editSeason', 'invite', 'manageMembers',
  'manageDevices', 'manageRecovery', 'recover', 'deleteGame', 'exportArchive', 'dissolveTeam', 'forceHandoff']);

// Deny unknown actions. Reading another scorer's game never requires writing authority.
export function can(role, action, { responsible = false, currentWriter = false } = {}) {
  if (!ROLES.includes(role)) return false;
  if (readActions.has(action)) return true;
  if (managementActions.has(action)) return role === 'admin';
  if (action === 'importHistory' || action === 'createGame' || action === 'editRoster') return role === 'admin' || role === 'scorer';
  if (action === 'editGame' || action === 'editGameDate' || action === 'handoff') {
    return currentWriter === true && (role === 'admin' || (role === 'scorer' && responsible === true));
  }
  if (action === 'manageHistoricalAccess') return role === 'admin' || (role === 'scorer' && responsible === true);
  return false;
}
