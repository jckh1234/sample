'use strict';

const fs = require('fs');
const path = require('path');

/**
 * Reads stateYYMMDD.txt files from the day after lastReadDate through yesterday.
 * Missing files are skipped. Returns the last date passed to saveToDb
 * (local midnight), or lastReadDate when no file in the range exists.
 * The caller must persist that calendar date for the next run.
 *
 * @param {string} folderPath Folder that contains stateYYMMDD.txt files.
 * @param {Date|string} lastReadDate Local Date, or a YYYY-MM-DD string.
 * @returns {Date} Last saved file date, at local midnight.
 */
function processStateFiles(folderPath, lastReadDate) {
    const endDate = addDays(startOfLocalDay(new Date()), -1);
    let cursor = addDays(toLocalDate(lastReadDate), 1);
    let lastProcessed = toLocalDate(lastReadDate);

    while (cursor.getTime() <= endDate.getTime()) {
        const fileName = 'state' + formatYyMmDd(cursor) + '.txt';
        const filePath = path.join(folderPath, fileName);

        if (fs.existsSync(filePath)) {
            const contents = fs.readFileSync(filePath, 'utf8');
            saveToDb(fileName, contents, cursor);
            lastProcessed = cursor;
        }

        cursor = addDays(cursor, 1);
    }

    return lastProcessed;
}

// Stub only. Replace this body with the real database write.
function saveToDb(fileName, contents, fileDate) {
}

function toLocalDate(value) {
    if (value instanceof Date) {
        if (isNaN(value.getTime())) {
            throw new TypeError('lastReadDate is invalid');
        }
        return new Date(value.getFullYear(), value.getMonth(), value.getDate());
    }

    if (typeof value === 'string') {
        const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
        if (!match) {
            throw new TypeError('lastReadDate must be a Date or YYYY-MM-DD string');
        }
        const year = Number(match[1]);
        const month = Number(match[2]);
        const day = Number(match[3]);
        const date = new Date(year, month - 1, day);
        if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
            throw new TypeError('lastReadDate is not a real calendar date');
        }
        return date;
    }

    throw new TypeError('lastReadDate must be a Date or YYYY-MM-DD string');
}

function startOfLocalDay(date) {
    return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function addDays(date, days) {
    const next = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    next.setDate(next.getDate() + days);
    return next;
}

function formatYyMmDd(date) {
    const yy = String(date.getFullYear() % 100).padStart(2, '0');
    const mm = String(date.getMonth() + 1).padStart(2, '0');
    const dd = String(date.getDate()).padStart(2, '0');
    return yy + mm + dd;
}

module.exports = {
    processStateFiles: processStateFiles
};
