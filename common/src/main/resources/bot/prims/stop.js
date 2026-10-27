module.exports = ctx => {
  const { done } = ctx

  // Whatever it was doing gone, and the ones held after it: a walk stopped, a dig stopped, a wait no
  // longer waited on. Then idle.
  function stop (action) {
    done(action)
  }

  return stop
}
