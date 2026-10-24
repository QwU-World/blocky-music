import fs from "node:fs";
import path from "node:path";

const [, , inputPath, outputDirectory] = process.argv;
if (!inputPath || !outputDirectory) {
    throw new Error("Usage: node convert_blockbench_player_animations.mjs <input.bbmodel> <output-directory>");
}

const model = JSON.parse(fs.readFileSync(inputPath, "utf8"));
const groups = new Map((model.groups ?? []).map(group => [group.uuid, group]));
const parentByUuid = new Map();

function indexParents(items, parentUuid = null) {
    for (const item of items ?? []) {
        if (typeof item !== "object" || item === null || !item.uuid) {
            continue;
        }
        parentByUuid.set(item.uuid, parentUuid);
        indexParents(item.children, item.uuid);
    }
}
indexParents(model.outliner);

const PARTS = [
    {source: "Head", output: "head", pivot: [0, 0, 0]},
    {source: "Body", output: "torso", pivot: [0, 0, 0]},
    {source: "LeftArm", output: "leftArm", pivot: [5, 2, 0]},
    {source: "RightArm", output: "rightArm", pivot: [-5, 2, 0]},
    {source: "LeftLeg", output: "leftLeg", pivot: [1.9, 12, 0.1]},
    {source: "RightLeg", output: "rightLeg", pivot: [-1.9, 12, 0.1]}
];
const ANIMATIONS = [
    {sources: ["idle"], outputs: [{name: "idle", loop: true}]},
    {sources: ["1 button"], outputs: [{name: "lane_0", loop: false}, {name: "hold_0", loop: true}]},
    // Older approved models called the second working clip "1 button2"; keep that
    // fallback while preferring the current "2 button" name. The separately named
    // archival fourth-button clip is deliberately never matched.
    {sources: ["1 button2", "2 button"], outputs: [{name: "lane_1", loop: false}, {name: "hold_1", loop: true}]},
    {sources: ["3 button"], outputs: [{name: "lane_2", loop: false}, {name: "hold_2", loop: true}]},
    {sources: ["4 button"], outputs: [{name: "lane_3", loop: false}, {name: "hold_3", loop: true}]}
];

const identity = [1, 0, 0, 0, 1, 0, 0, 0, 1];
const zero = [0, 0, 0];

function number(value) {
    const parsed = Number.parseFloat(value ?? 0);
    return Number.isFinite(parsed) ? parsed : 0;
}

function vector(value, fallback = zero) {
    if (!Array.isArray(value)) {
        return [...fallback];
    }
    return [number(value[0]), number(value[1]), number(value[2])];
}

function dataPoint(keyframe) {
    const point = keyframe?.data_points?.[0] ?? {};
    return [number(point.x), number(point.y), number(point.z)];
}

function handle(keyframe, key, axis) {
    const values = keyframe?.[key];
    return Array.isArray(values) ? number(values[axis]) : 0;
}

function cubic(a, b, c, d, t) {
    const oneMinus = 1 - t;
    return oneMinus ** 3 * a
        + 3 * oneMinus ** 2 * t * b
        + 3 * oneMinus * t ** 2 * c
        + t ** 3 * d;
}

function bezierValue(left, right, time, axis) {
    const t0 = number(left.time);
    const t1 = number(right.time);
    const v0 = dataPoint(left)[axis];
    const v1 = dataPoint(right)[axis];
    const x1 = t0 + handle(left, "bezier_right_time", axis);
    const x2 = t1 + handle(right, "bezier_left_time", axis);
    const y1 = v0 + handle(left, "bezier_right_value", axis);
    const y2 = v1 + handle(right, "bezier_left_value", axis);
    let low = 0;
    let high = 1;
    for (let iteration = 0; iteration < 24; iteration++) {
        const middle = (low + high) / 2;
        if (cubic(t0, x1, x2, t1, middle) < time) {
            low = middle;
        } else {
            high = middle;
        }
    }
    return cubic(v0, y1, y2, v1, (low + high) / 2);
}

function sampleChannel(animator, channel, time) {
    const keyframes = (animator?.keyframes ?? [])
        .filter(keyframe => keyframe.channel === channel)
        .sort((left, right) => number(left.time) - number(right.time));
    if (keyframes.length === 0) {
        return [...zero];
    }
    if (time <= number(keyframes[0].time)) {
        return dataPoint(keyframes[0]);
    }
    const last = keyframes[keyframes.length - 1];
    if (time >= number(last.time)) {
        return dataPoint(last);
    }
    for (let index = 0; index < keyframes.length - 1; index++) {
        const left = keyframes[index];
        const right = keyframes[index + 1];
        if (time > number(right.time)) {
            continue;
        }
        if (left.interpolation === "step") {
            return dataPoint(left);
        }
        if (left.interpolation === "bezier" || right.interpolation === "bezier") {
            return [0, 1, 2].map(axis => bezierValue(left, right, time, axis));
        }
        const span = Math.max(1.0e-7, number(right.time) - number(left.time));
        const alpha = (time - number(left.time)) / span;
        const a = dataPoint(left);
        const b = dataPoint(right);
        return a.map((value, axis) => value + (b[axis] - value) * alpha);
    }
    return dataPoint(last);
}

function add(a, b) {
    return [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
}

function subtract(a, b) {
    return [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
}

function multiplyMatrix(left, right) {
    const result = new Array(9).fill(0);
    for (let row = 0; row < 3; row++) {
        for (let column = 0; column < 3; column++) {
            for (let inner = 0; inner < 3; inner++) {
                result[row * 3 + column] += left[row * 3 + inner] * right[inner * 3 + column];
            }
        }
    }
    return result;
}

function transform(matrix, value) {
    return [
        matrix[0] * value[0] + matrix[1] * value[1] + matrix[2] * value[2],
        matrix[3] * value[0] + matrix[4] * value[1] + matrix[5] * value[2],
        matrix[6] * value[0] + matrix[7] * value[1] + matrix[8] * value[2]
    ];
}

function rotationMatrix([x, y, z]) {
    const sx = Math.sin(x);
    const cx = Math.cos(x);
    const sy = Math.sin(y);
    const cy = Math.cos(y);
    const sz = Math.sin(z);
    const cz = Math.cos(z);
    return [
        cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
        sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
        -sy, cy * sx, cy * cx
    ];
}

function eulerFromMatrix(matrix) {
    const y = Math.asin(Math.max(-1, Math.min(1, -matrix[6])));
    if (Math.abs(Math.cos(y)) > 1.0e-6) {
        return [Math.atan2(matrix[7], matrix[8]), y, Math.atan2(matrix[3], matrix[0])];
    }
    return [Math.atan2(-matrix[5], matrix[4]), y, 0];
}

function closestAngle(angle, previous) {
    if (previous === undefined) {
        return angle;
    }
    while (angle - previous > Math.PI) angle -= Math.PI * 2;
    while (angle - previous < -Math.PI) angle += Math.PI * 2;
    return angle;
}

function minecraftPivot(group) {
    const origin = vector(group?.origin);
    return [-origin[0], 24 - origin[1], origin[2]];
}

function localPose(animator, time) {
    const position = sampleChannel(animator, "position", time);
    const rotation = sampleChannel(animator, "rotation", time).map(value => value * Math.PI / 180);
    return {
        position: [-position[0], -position[1], position[2]],
        rotation: rotationMatrix([-rotation[0], -rotation[1], rotation[2]])
    };
}

function round(value) {
    const rounded = Math.abs(value) < 5.0e-7 ? 0 : Math.round(value * 1_000_000) / 1_000_000;
    return Object.is(rounded, -0) ? 0 : rounded;
}

function buildClip(animation, outputName, loop) {
    const animators = new Map(Object.values(animation.animators ?? {}).map(animator => [animator.name, animator]));
    const groupByName = new Map([...groups.values()].map(group => [group.name, group]));
    const statesByTick = [];
    const previousEuler = new Map();
    const finalTick = 13;

    for (let tick = 0; tick <= finalTick; tick++) {
        const time = tick === finalTick ? number(animation.length) : Math.min(number(animation.length), tick / 20);
        const worldByUuid = new Map();

        function worldPose(group) {
            if (!group) {
                return {rotation: identity, translation: zero};
            }
            if (worldByUuid.has(group.uuid)) {
                return worldByUuid.get(group.uuid);
            }
            const parent = groups.get(parentByUuid.get(group.uuid));
            const parentPose = worldPose(parent);
            const bindPivot = minecraftPivot(group);
            const local = localPose(animators.get(group.name), time);
            const worldRotation = multiplyMatrix(parentPose.rotation, local.rotation);
            const animatedPivot = add(
                add(transform(parentPose.rotation, bindPivot), parentPose.translation),
                transform(parentPose.rotation, local.position)
            );
            const pose = {
                rotation: worldRotation,
                translation: subtract(animatedPivot, transform(worldRotation, bindPivot))
            };
            worldByUuid.set(group.uuid, pose);
            return pose;
        }

        const state = new Map();
        for (const part of PARTS) {
            const group = groupByName.get(part.source);
            if (!group) {
                throw new Error(`Missing Blockbench group: ${part.source}`);
            }
            const pose = worldPose(group);
            const targetPosition = add(transform(pose.rotation, part.pivot), pose.translation);
            let euler = eulerFromMatrix(pose.rotation);
            const previous = previousEuler.get(part.output);
            euler = euler.map((value, axis) => closestAngle(value, previous?.[axis]));
            previousEuler.set(part.output, euler);
            state.set(part.output, {position: targetPosition, rotation: euler});
        }
        statesByTick.push(state);
    }

    const moves = [];
    for (let tick = 0; tick <= finalTick; tick++) {
        for (const part of PARTS) {
            const state = statesByTick[tick].get(part.output);
            const delta = subtract(state.position, part.pivot);
            moves.push({
                tick,
                easing: "LINEAR",
                [part.output]: {
                    pitch: round(-state.rotation[0]),
                    yaw: round(-state.rotation[1]),
                    roll: round(state.rotation[2])
                }
            });
            moves.push({
                tick,
                easing: "LINEAR",
                [part.output]: {
                    x: round(part.pivot[0] - delta[0]),
                    y: round(part.pivot[1] + delta[1]),
                    z: round(part.pivot[2] + delta[2])
                }
            });
        }
    }

    return {
        version: 3,
        name: outputName,
        emote: {
            beginTick: 0,
            endTick: finalTick,
            stopTick: finalTick + 1,
            isLoop: loop,
            returnTick: 0,
            nsfw: false,
            degrees: false,
            moves
        }
    };
}

fs.mkdirSync(outputDirectory, {recursive: true});
for (const entry of ANIMATIONS) {
    const animation = (model.animations ?? []).find(candidate => entry.sources.includes(candidate.name));
    if (!animation) {
        throw new Error(`Missing Blockbench animation: ${entry.sources.join(" or ")}`);
    }
    for (const output of entry.outputs) {
        const clip = buildClip(animation, output.name, output.loop);
        const destination = path.join(outputDirectory, `${output.name}.json`);
        fs.writeFileSync(destination, `${JSON.stringify(clip, null, 2)}\n`, "utf8");
        console.log(`Generated ${destination}`);
    }
}
