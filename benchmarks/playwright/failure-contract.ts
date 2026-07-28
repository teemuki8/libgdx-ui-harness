export interface FailureExpectation {
  category: string;
  harnessCode: string;
  harnessEvidencePath: string;
  harnessEvidenceValue: string;
  playwrightErrorName: string;
  playwrightMessage: string;
}

export function matchesExpectedFailure(
  failure: unknown, expected: FailureExpectation,
): failure is Error {
  return failure instanceof Error
    && failure.name === expected.playwrightErrorName
    && failure.message.includes(expected.playwrightMessage);
}
