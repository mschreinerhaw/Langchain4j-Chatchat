const failureStatusValues = new Set(['false', 'fail', 'failed', 'failure', 'error', 'exception']);

function resultScopes(result) {
  if (!result || typeof result !== 'object') return [];
  const scopes = [result];
  if (result.data && typeof result.data === 'object') scopes.push(result.data);
  if (result.result && typeof result.result === 'object') scopes.push(result.result);
  return scopes;
}

export function isTestFailure(result) {
  return resultScopes(result).some(scope => {
    if (scope.success === false || scope.ok === false || scope.passed === false) return true;
    if (scope.connected === false || scope.available === false || scope.reachable === false) return true;
    const status = String(scope.status || scope.resultStatus || '').trim().toLowerCase();
    if (failureStatusValues.has(status)) return true;
    if (scope.error || scope.errorMessage) return scope.success !== true && scope.ok !== true;
    return false;
  });
}

export function testResultMessage(result, fallback = '测试完成。') {
  for (const scope of resultScopes(result)) {
    const message = scope.errorMessage || scope.error || scope.message || scope.detail || scope.reason;
    if (message) return String(message);
  }
  return fallback;
}

export function buildTestNotification(result, options = {}) {
  const failed = isTestFailure(result);
  return {
    type: failed ? 'danger' : 'success',
    title: failed ? (options.failureTitle || '测试失败') : (options.successTitle || '测试成功'),
    message: failed
      ? testResultMessage(result, options.failureMessage || '测试未通过。')
      : testResultMessage(result, options.successMessage || '测试完成。')
  };
}
