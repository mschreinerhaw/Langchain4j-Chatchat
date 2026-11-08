// Runtime decides whether repair is authorized. Native transport only enforces its actual stopped receipt.
export const runtimeRepairStopped=receipt=>receipt?.executorStopped===true&&receipt?.resultAvailable!==true
 &&receipt?.executionFeedback?.repair?.action==='STOP';
