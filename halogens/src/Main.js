"use strict";

exports.showDate = function (ds) {
    let d = new Date(ds)
    let lang = navigator.language
    return `${d.toLocaleString(lang, {weekday: 'short', year: 'numeric', month: 'short', day: 'numeric'})}  @ ${d.toLocaleTimeString()}`
}

exports.weekAhead = function (prefix) {
    const now = new Date();
    const fut = new Date();
    fut.setDate(fut.getDate() + 7);
    return `${prefix}?from=${now.toISOString().slice(0,10)}&until=${fut.toISOString().slice(0,10)}`
}

exports.monthAhead = function (prefix) {
    const now = new Date();
    const fut = new Date();
    fut.setDate(fut.getDate() + 31);
    return `${prefix}?from=${now.toISOString().slice(0,10)}&until=${fut.toISOString().slice(0,10)}`
}