"use strict";

const { ExperimentAPI } = ChromeUtils.importESModule(
  "resource://nimbus/ExperimentAPI.sys.mjs"
);
const { ExperimentFakes } = ChromeUtils.importESModule(
  "resource://testing-common/NimbusTestUtils.sys.mjs"
);
const { TestUtils } = ChromeUtils.importESModule(
  "resource://testing-common/TestUtils.sys.mjs"
);
const { RemoteSettingsExperimentLoader } = ChromeUtils.importESModule(
  "resource://nimbus/lib/RemoteSettingsExperimentLoader.sys.mjs"
);
const COLLECTION_ID_PREF = "messaging-system.rsexperimentloader.collection_id";

/**
 * #getExperiment
 */
add_task(async function test_getExperiment_fromChild_slug() {
  const sandbox = sinon.createSandbox();
  const manager = ExperimentFakes.manager();
  const expected = ExperimentFakes.experiment("foo");

  await manager.onStartup();

  sandbox.stub(ExperimentAPI, "_store").get(() => ExperimentFakes.childStore());

  await manager.store.addEnrollment(expected);

  // Wait to sync to child
  await TestUtils.waitForCondition(
    () => ExperimentAPI.getExperiment({ slug: "foo" }),
    "Wait for child to sync"
  );

  Assert.equal(
    ExperimentAPI.getExperiment({ slug: "foo" }).slug,
    expected.slug,
    "should return an experiment by slug"
  );

  Assert.deepEqual(
    ExperimentAPI.getExperiment({ slug: "foo" }).branch,
    expected.branch,
    "should return the right branch by slug"
  );

  sandbox.restore();
});

add_task(async function test_getExperiment_fromParent_slug() {
  const sandbox = sinon.createSandbox();
  const manager = ExperimentFakes.manager();
  const expected = ExperimentFakes.experiment("foo");

  await manager.onStartup();
  sandbox.stub(ExperimentAPI, "_store").get(() => manager.store);
  await ExperimentAPI.ready();

  await manager.store.addEnrollment(expected);

  Assert.equal(
    ExperimentAPI.getExperiment({ slug: "foo" }).slug,
    expected.slug,
    "should return an experiment by slug"
  );

  sandbox.restore();
});

add_task(async function test_getExperimentMetaData() {
  const sandbox = sinon.createSandbox();
  const manager = ExperimentFakes.manager();
  const expected = ExperimentFakes.experiment("foo");
  let exposureStub = sandbox.stub(ExperimentAPI, "recordExposureEvent");

  await manager.onStartup();
  sandbox.stub(ExperimentAPI, "_store").get(() => manager.store);
  await ExperimentAPI.ready();

  await manager.store.addEnrollment(expected);

  let metadata = ExperimentAPI.getExperimentMetaData({ slug: expected.slug });

  Assert.equal(
    Object.keys(metadata.branch).length,
    1,
    "Should only expose one property"
  );
  Assert.equal(
    metadata.branch.slug,
    expected.branch.slug,
    "Should have the slug prop"
  );

  Assert.ok(exposureStub.notCalled, "Not called for this method");

  sandbox.restore();
});

add_task(async function test_getRolloutMetaData() {
  const sandbox = sinon.createSandbox();
  const manager = ExperimentFakes.manager();
  const expected = ExperimentFakes.rollout("foo");
  let exposureStub = sandbox.stub(ExperimentAPI, "recordExposureEvent");

  await manager.onStartup();
  sandbox.stub(ExperimentAPI, "_store").get(() => manager.store);
  await ExperimentAPI.ready();

  await manager.store.addEnrollment(expected);

  let metadata = ExperimentAPI.getExperimentMetaData({ slug: expected.slug });

  Assert.equal(
    Object.keys(metadata.branch).length,
    1,
    "Should only expose one property"
  );
  Assert.equal(
    metadata.branch.slug,
    expected.branch.slug,
    "Should have the slug prop"
  );

  Assert.ok(exposureStub.notCalled, "Not called for this method");

  sandbox.restore();
});

add_task(function test_getExperimentMetaData_safe() {
  const sandbox = sinon.createSandbox();
  let exposureStub = sandbox.stub(ExperimentAPI, "recordExposureEvent");

  sandbox.stub(ExperimentAPI._store, "get").throws();
  sandbox.stub(ExperimentAPI._store, "getExperimentForFeature").throws();

  try {
    let metadata = ExperimentAPI.getExperimentMetaData({ slug: "foo" });
    Assert.equal(metadata, null, "Should not throw");
  } catch (e) {
    Assert.ok(false, "Error should be caught in ExperimentAPI");
  }

  Assert.ok(ExperimentAPI._store.get.calledOnce, "Sanity check");

  try {
    let metadata = ExperimentAPI.getExperimentMetaData({ featureId: "foo" });
    Assert.equal(metadata, null, "Should not throw");
  } catch (e) {
    Assert.ok(false, "Error should be caught in ExperimentAPI");
  }

  Assert.ok(
    ExperimentAPI._store.getExperimentForFeature.calledOnce,
    "Sanity check"
  );

  Assert.ok(exposureStub.notCalled, "Not called for this feature");

  sandbox.restore();
});

add_task(async function test_getExperiment_feature() {
  const sandbox = sinon.createSandbox();
  const manager = ExperimentFakes.manager();
  const expected = ExperimentFakes.experiment("foo", {
    branch: {
      slug: "treatment",
      ratio: 1,
      features: [{ featureId: "cfr", value: {} }],
      feature: {
        featureId: "unused-feature-id-for-legacy-support",
        enabled: false,
        value: {},
      },
    },
  });

  await manager.onStartup();

  sandbox.stub(ExperimentAPI, "_store").get(() => ExperimentFakes.childStore());
  let exposureStub = sandbox.stub(ExperimentAPI, "recordExposureEvent");

  await manager.store.addEnrollment(expected);

  // Wait to sync to child
  await TestUtils.waitForCondition(
    () => ExperimentAPI.getExperiment({ featureId: "cfr" }),
    "Wait for child to sync"
  );

  Assert.equal(
    ExperimentAPI.getExperiment({ featureId: "cfr" }).slug,
    expected.slug,
    "should return an experiment by featureId"
  );

  Assert.deepEqual(
    ExperimentAPI.getExperiment({ featureId: "cfr" }).branch,
    expected.branch,
    "should return the right branch by featureId"
  );

  Assert.ok(exposureStub.notCalled, "Not called by default");

  sandbox.restore();
});

add_task(async function test_getExperiment_safe() {
  const sandbox = sinon.createSandbox();
  sandbox.stub(ExperimentAPI._store, "getExperimentForFeature").throws();

  try {
    Assert.equal(
      ExperimentAPI.getExperiment({ featureId: "foo" }),
      null,
      "It should not fail even when it throws."
    );
  } catch (e) {
    Assert.ok(false, "Error should be caught by ExperimentAPI");
  }

  sandbox.restore();
});

add_task(async function test_getExperiment_featureAccess() {
  const sandbox = sinon.createSandbox();
  const expected = ExperimentFakes.experiment("foo", {
    branch: {
      slug: "treatment",
      value: { title: "hi" },
      features: [{ featureId: "cfr", value: { message: "content" } }],
    },
  });
  const stub = sandbox
    .stub(ExperimentAPI._store, "getExperimentForFeature")
    .returns(expected);

  let { branch } = ExperimentAPI.getExperiment({ featureId: "cfr" });

  Assert.equal(branch.slug, "treatment");
  let feature = branch.cfr;
  Assert.ok(feature, "Should allow to access by featureId");
  Assert.equal(feature.value.message, "content");

  stub.restore();
});

add_task(async function test_getExperiment_featureAccess_backwardsCompat() {
  const sandbox = sinon.createSandbox();
  const expected = ExperimentFakes.experiment("foo", {
    branch: {
      slug: "treatment",
      feature: { featureId: "cfr", value: { message: "content" } },
    },
  });
  const stub = sandbox
    .stub(ExperimentAPI._store, "getExperimentForFeature")
    .returns(expected);

  let { branch } = ExperimentAPI.getExperiment({ featureId: "cfr" });

  Assert.equal(branch.slug, "treatment");
  let feature = branch.cfr;
  Assert.ok(feature, "Should allow to access by featureId");
  Assert.equal(feature.value.message, "content");

  stub.restore();
});

/**
 * #getRecipe
 */
add_task(async function test_getRecipe() {
  const sandbox = sinon.createSandbox();
  const RECIPE = ExperimentFakes.recipe("foo");
  const collectionName = Services.prefs.getStringPref(COLLECTION_ID_PREF);
  sandbox.stub(ExperimentAPI._remoteSettingsClient, "get").resolves([RECIPE]);

  const recipe = await ExperimentAPI.getRecipe("foo");
  Assert.deepEqual(
    recipe,
    RECIPE,
    "should return an experiment recipe if found"
  );
  Assert.equal(
    ExperimentAPI._remoteSettingsClient.collectionName,
    collectionName,
    "Loaded the expected collection"
  );

  sandbox.restore();
});

add_task(async function test_getRecipe_Failure() {
  const sandbox = sinon.createSandbox();
  sandbox.stub(ExperimentAPI._remoteSettingsClient, "get").throws();

  const recipe = await ExperimentAPI.getRecipe("foo");
  Assert.equal(recipe, undefined, "should return undefined if RS throws");

  sandbox.restore();
});

/**
 * #getAllBranches
 */
add_task(async function test_getAllBranches() {
  const sandbox = sinon.createSandbox();
  const RECIPE = ExperimentFakes.recipe("foo");
  sandbox.stub(ExperimentAPI._remoteSettingsClient, "get").resolves([RECIPE]);

  const branches = await ExperimentAPI.getAllBranches("foo");
  Assert.deepEqual(
    branches,
    RECIPE.branches,
    "should return all branches if found a recipe"
  );

  sandbox.restore();
});

// API used by Messaging System
add_task(async function test_getAllBranches_featureIdAccessor() {
  const sandbox = sinon.createSandbox();
  const RECIPE = ExperimentFakes.recipe("foo");
  sandbox.stub(ExperimentAPI._remoteSettingsClient, "get").resolves([RECIPE]);

  const branches = await ExperimentAPI.getAllBranches("foo");
  Assert.deepEqual(
    branches,
    RECIPE.branches,
    "should return all branches if found a recipe"
  );
  branches.forEach(branch => {
    Assert.equal(
      branch.testFeature.featureId,
      "testFeature",
      "Should use the experimentBranchAccessor proxy getter"
    );
  });

  sandbox.restore();
});

// For schema version before 1.6.2 branch.feature was accessed
// instead of branch.features
add_task(async function test_getAllBranches_backwardsCompat() {
  const sandbox = sinon.createSandbox();
  const RECIPE = ExperimentFakes.recipe("foo");
  delete RECIPE.branches[0].features;
  delete RECIPE.branches[1].features;
  let feature = {
    featureId: "backwardsCompat",
    value: {
      enabled: true,
    },
  };
  RECIPE.branches[0].feature = feature;
  RECIPE.branches[1].feature = feature;
  sandbox.stub(ExperimentAPI._remoteSettingsClient, "get").resolves([RECIPE]);

  const branches = await ExperimentAPI.getAllBranches("foo");
  Assert.deepEqual(
    branches,
    RECIPE.branches,
    "should return all branches if found a recipe"
  );
  branches.forEach(branch => {
    Assert.equal(
      branch.backwardsCompat.featureId,
      "backwardsCompat",
      "Should use the experimentBranchAccessor proxy getter"
    );
  });

  sandbox.restore();
});

add_task(async function test_getAllBranches_Failure() {
  const sandbox = sinon.createSandbox();
  sandbox.stub(ExperimentAPI._remoteSettingsClient, "get").throws();

  const branches = await ExperimentAPI.getAllBranches("foo");
  Assert.equal(branches, undefined, "should return undefined if RS throws");

  sandbox.restore();
});

/**
 * Store events
 */
add_task(async function test_addEnrollment_eventEmit_add() {
  const sandbox = sinon.createSandbox();
  const slugStub = sandbox.stub();
  const featureStub = sandbox.stub();
  const experiment = ExperimentFakes.experiment("foo", {
    branch: {
      slug: "variant",
      features: [{ featureId: "purple", value: null }],
    },
  });
  const store = ExperimentFakes.store();
  sandbox.stub(ExperimentAPI, "_store").get(() => store);

  await store.init();
  await ExperimentAPI.ready();

  store.on("update:foo", slugStub);
  store.on("featureUpdate:purple", featureStub);

  await store.addEnrollment(experiment);

  Assert.equal(
    slugStub.callCount,
    1,
    "should call 'update' callback for slug when experiment is added"
  );
  Assert.equal(slugStub.firstCall.args[1].slug, experiment.slug);
  Assert.equal(
    featureStub.callCount,
    1,
    "should call 'featureUpdate' callback for featureId when an experiment is added"
  );
  Assert.equal(featureStub.firstCall.args[0], "featureUpdate:purple");
  Assert.equal(featureStub.firstCall.args[1], "experiment-updated");

  store.off("update:foo", slugStub);
  store.off("featureUpdate:purple", featureStub);
  sandbox.restore();
});

add_task(async function test_updateExperiment_eventEmit_add_and_update() {
  const sandbox = sinon.createSandbox();
  const slugStub = sandbox.stub();
  const featureStub = sandbox.stub();
  const experiment = ExperimentFakes.experiment("foo", {
    branch: {
      slug: "variant",
      features: [{ featureId: "purple", value: null }],
    },
  });
  const store = ExperimentFakes.store();
  sandbox.stub(ExperimentAPI, "_store").get(() => store);

  await store.init();
  await ExperimentAPI.ready();

  await store.addEnrollment(experiment);

  store.on("update:foo", slugStub);
  store._onFeatureUpdate("purple", featureStub);

  store.updateExperiment(experiment.slug, experiment);

  await TestUtils.waitForCondition(
    () => featureStub.callCount == 2,
    "Wait for `on` method to notify callback about the `add` event."
  );
  // Called twice, once when attaching the event listener (because there is an
  // existing experiment with that name) and 2nd time for the update event
  Assert.equal(slugStub.firstCall.args[1].slug, experiment.slug);
  Assert.equal(featureStub.callCount, 2, "Called twice for feature");
  Assert.equal(featureStub.firstCall.args[0], "featureUpdate:purple");
  Assert.equal(featureStub.firstCall.args[1], "experiment-updated");

  store.off("update:foo", slugStub);
  store._offFeatureUpdate("featureUpdate:purple", featureStub);
});

add_task(async function test_updateExperiment_eventEmit_off() {
  const sandbox = sinon.createSandbox();
  const slugStub = sandbox.stub();
  const featureStub = sandbox.stub();
  const experiment = ExperimentFakes.experiment("foo", {
    branch: {
      slug: "variant",
      features: [{ featureId: "purple", value: null }],
    },
  });
  const store = ExperimentFakes.store();
  sandbox.stub(ExperimentAPI, "_store").get(() => store);

  await store.init();
  await ExperimentAPI.ready();

  store.on("update:foo", slugStub);
  store.on("featureUpdate:purple", featureStub);

  await store.addEnrollment(experiment);

  store.off("update:foo", slugStub);
  store.off("featureUpdate:purple", featureStub);

  store.updateExperiment(experiment.slug, experiment);

  Assert.equal(slugStub.callCount, 1, "Called only once before `off`");
  Assert.equal(featureStub.callCount, 1, "Called only once before `off`");

  sandbox.restore();
});

add_task(async function test_getActiveBranch() {
  const sandbox = sinon.createSandbox();
  const store = ExperimentFakes.store();
  sandbox.stub(ExperimentAPI, "_store").get(() => store);
  const experiment = ExperimentFakes.experiment("foo", {
    branch: {
      slug: "variant",
      features: [{ featureId: "green", value: null }],
    },
  });

  await store.init();
  await store.addEnrollment(experiment);

  Assert.deepEqual(
    ExperimentAPI.getActiveBranch({ featureId: "green" }),
    experiment.branch,
    "Should return feature of active experiment"
  );

  sandbox.restore();
});

add_task(async function test_getActiveBranch_safe() {
  const sandbox = sinon.createSandbox();
  sandbox.stub(ExperimentAPI._store, "getAllActiveExperiments").throws();

  try {
    Assert.equal(
      ExperimentAPI.getActiveBranch({ featureId: "green" }),
      null,
      "Should not throw"
    );
  } catch (e) {
    Assert.ok(false, "Should catch error in ExperimentAPI");
  }

  sandbox.restore();
});

add_task(async function test_getActiveBranch_storeFailure() {
  const store = ExperimentFakes.store();
  const sandbox = sinon.createSandbox();
  sandbox.stub(ExperimentAPI, "_store").get(() => store);
  const experiment = ExperimentFakes.experiment("foo", {
    branch: {
      slug: "variant",
      features: [{ featureId: "green" }],
    },
  });

  await store.init();
  await store.addEnrollment(experiment);
  // Adding stub later because `addEnrollment` emits update events
  const stub = sandbox.stub(store, "emit");
  // Call getActiveBranch to trigger an activation event
  sandbox.stub(store, "getAllActiveExperiments").throws();
  try {
    ExperimentAPI.getActiveBranch({ featureId: "green" });
  } catch (e) {
    /* This is expected */
  }

  Assert.equal(stub.callCount, 0, "Not called if store somehow fails");
  sandbox.restore();
});

add_task(async function test_getActiveBranch_noActivationEvent() {
  const store = ExperimentFakes.store();
  const sandbox = sinon.createSandbox();
  sandbox.stub(ExperimentAPI, "_store").get(() => store);
  const experiment = ExperimentFakes.experiment("foo", {
    branch: {
      slug: "variant",
      features: [{ featureId: "green" }],
    },
  });

  await store.init();
  await store.addEnrollment(experiment);
  // Adding stub later because `addEnrollment` emits update events
  const stub = sandbox.stub(store, "emit");
  // Call getActiveBranch to trigger an activation event
  ExperimentAPI.getActiveBranch({ featureId: "green" });

  Assert.equal(stub.callCount, 0, "Not called: sendExposureEvent is false");
  sandbox.restore();
});

/**
 * #getFirefoxLabsOptInRecipes
 */
add_task(async function test_getFirefoxLabsOptInRecipes() {
  const sandbox = sinon.createSandbox();
  const manager = ExperimentFakes.manager();
  sandbox.stub(RemoteSettingsExperimentLoader, "updatingRecipes").resolves();
  sandbox.stub(ExperimentAPI, "_manager").get(() => manager);

  const actualRecipes = [
    ExperimentFakes.recipe("opt-in-one", {
      targeting: "true",
      isFirefoxLabsOptIn: true,
      bucketConfig: {
        ...ExperimentFakes.recipe.bucketConfig,
        count: 1000,
      },
    }),
    ExperimentFakes.recipe("opt-in-two", {
      targeting: "true",
      isFirefoxLabsOptIn: true,
      bucketConfig: {
        ...ExperimentFakes.recipe.bucketConfig,
        count: 1000,
      },
    }),
  ];

  manager.optInRecipes = actualRecipes;

  const expectedRecipes = await ExperimentAPI.getFirefoxLabsOptInRecipes();

  Assert.deepEqual(
    expectedRecipes,
    actualRecipes,
    "Should return all opt in recipes that match targeting and bucketing"
  );

  sandbox.restore();
});

/**
 * #enrollInFirefoxLabsOptInRecipe
 */
add_task(async function test_enrollInFirefoxLabsOptInRecipe() {
  const sandbox = sinon.createSandbox();
  const manager = ExperimentFakes.manager();
  const managerEnrollStub = sandbox.stub(manager, "enroll").resolves(true);
  sandbox.stub(RemoteSettingsExperimentLoader, "updatingRecipes").resolves();
  sandbox.stub(ExperimentAPI, "_manager").get(() => manager);

  const optInRecipes = [
    ExperimentFakes.recipe("opt-in-one", {
      targeting: "true",
      isFirefoxLabsOptIn: true,
      branches: [
        {
          slug: "branch-slug-one",
          ratio: 1,
          features: [{ featureId: "pink", value: {} }],
        },
      ],
    }),
    ExperimentFakes.recipe("opt-in-two", {
      targeting: "true",
      isFirefoxLabsOptIn: true,
      branches: [
        {
          slug: "branch-slug-two",
          ratio: 1,
          features: [{ featureId: "pink", value: {} }],
        },
      ],
    }),
  ];

  manager.optInRecipes = optInRecipes;

  await Assert.rejects(
    ExperimentAPI.enrollInFirefoxLabsOptInRecipe(),
    /ExperimentAPI.enrollInFirefoxLabsOptInRecipe must be called with slug and optInRecipeBranchSlug./,
    "Should throw when .enrollInFirefoxLabsOptInRecipe is called without a slug or optInRecipeBranchSlug argument"
  );

  await Assert.rejects(
    ExperimentAPI.enrollInFirefoxLabsOptInRecipe("opt-in-one"),
    /ExperimentAPI.enrollInFirefoxLabsOptInRecipe must be called with slug and optInRecipeBranchSlug./,
    "Should throw when .enrollInFirefoxLabsOptInRecipe is called without a optInRecipeBranchSlug argument"
  );

  await ExperimentAPI.enrollInFirefoxLabsOptInRecipe(
    "opt-in-one",
    "branch-slug-one"
  );
  Assert.ok(
    managerEnrollStub.calledOnceWith(optInRecipes[0], "rs-loader", {
      optInRecipeBranchSlug: "branch-slug-one",
    })
  );

  managerEnrollStub.reset();

  // The ExperimentAPI._manager.getSingleOptInRecipe(slug) made inside this call
  // should not return a recipe, hence the below enroll call won't be executed.
  await ExperimentAPI.enrollInFirefoxLabsOptInRecipe(
    "slug-non-existent",
    "branch-slug-one"
  );
  Assert.equal(
    managerEnrollStub.callCount,
    0,
    "Should not call the enroll function when no recipe is returned"
  );

  sandbox.restore();
});
